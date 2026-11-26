package com.tcl.csot.zhiye.service.repo;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 每个应用一个 bare 仓库：runtime/repos/{appId}.git，分支 main。
 * 一个版本对应 main 上的一次提交；构建产物按文件树哈希复用。
 */
@Service
public class GitService {
    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    public record CommitResult(String commit, String tree) {}
    public record FileEntry(String path, long size) {}

    private static final String MAIN = "refs/heads/main";
    private static final Set<String> TEMPLATE_SKIP = Set.of("node_modules", "dist", "target", ".zhiye", ".git", ".DS_Store");

    private final ZhiyeProperties props;

    public GitService(ZhiyeProperties props) { this.props = props; }

    public Path repoPath(String appId) { return props.runtimePath().resolve("repos").resolve(appId + ".git"); }

    private PersonIdent ident() { return new PersonIdent("织页", "bot@zhiye.local"); }

    private Repository openBare(String appId) throws IOException {
        return new FileRepositoryBuilder().setGitDir(repoPath(appId).toFile()).setBare().build();
    }

    public void initEmpty(String appId) throws Exception {
        Path p = repoPath(appId);
        Files.createDirectories(p.getParent());
        Git.init().setBare(true).setInitialBranch("main").setDirectory(p.toFile()).call().close();
    }

    /** 新建仓库，初始提交 = packages/app-template 的拷贝（不是一个版本）。 */
    public CommitResult initFromTemplate(String appId) throws Exception { return initFromTemplate(appId, "app-template"); }

    /** 新建干净的自由架构工作区（不预塞任何固定框架模板文件，由编码智能体按需求自由选择技术栈与目录结构）。 */
    public CommitResult initCleanWorkspace(String appId, String title) throws Exception {
        initEmpty(appId);
        Path tmp = Files.createTempDirectory("zhiye-clean-ws");
        try {
            Files.writeString(tmp.resolve(".gitignore"),
                    "node_modules/\ndist/\nbuild/\ntarget/\n__pycache__/\n.venv/\n.data/\n.zhiye/\n.env\n.env.*\n.DS_Store\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            String heading = (title == null || title.isBlank()) ? "Workspace" : title.trim();
            Files.writeString(tmp.resolve("README.md"),
                    "# " + heading + "\n\n由织页自由工程工作区初始化，可按需求自由选择技术栈、目录结构与 `zhiye.yaml` 工作流。\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            try (Git g = Git.init().setDirectory(tmp.toFile()).setInitialBranch("main").call()) {
                g.add().addFilepattern(".").call();
                g.commit().setAuthor(ident()).setCommitter(ident()).setMessage("初始化自由工作区").setSign(false).call();
                var results = g.push().setRemote(repoPath(appId).toAbsolutePath().toString())
                        .setRefSpecs(new RefSpec("+refs/heads/main:refs/heads/main")).call();
                for (var r : results)
                    for (var u : r.getRemoteUpdates())
                        if (u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK && u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE)
                            throw new IllegalStateException("初始化自由工作区失败：" + u.getStatus());
            }
            return head(appId).orElseThrow();
        } finally {
            try { deleteRecursively(tmp); } catch (IOException e) { log.warn("清理临时目录失败：{}", tmp, e); }
        }
    }

    /** templateName：packages/ 下的模板目录（app-template / skill-template） */
    public CommitResult initFromTemplate(String appId, String templateName) throws Exception {
        initEmpty(appId);
        Path template = props.rootPath().resolve("packages").resolve(templateName);
        Path tmp = Files.createTempDirectory("zhiye-tpl");
        try {
            copyTree(template, tmp, TEMPLATE_SKIP);
            try (Git g = Git.init().setDirectory(tmp.toFile()).setInitialBranch("main").call()) {
                g.add().addFilepattern(".").call();
                g.commit().setAuthor(ident()).setCommitter(ident()).setMessage("初始化应用模板").setSign(false).call();
                // 计划阶段换形态时仓库里已有上一个模板的初始提交（历史无关），要强制覆盖；
                // 推送失败不会抛异常，需要检查结果，否则会悄悄留着旧模板
                var results = g.push().setRemote(repoPath(appId).toAbsolutePath().toString())
                        .setRefSpecs(new RefSpec("+refs/heads/main:refs/heads/main")).call();
                for (var r : results)
                    for (var u : r.getRemoteUpdates())
                        if (u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK && u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE)
                            throw new IllegalStateException("写入模板失败：" + u.getStatus());
            }
            return head(appId).orElseThrow();
        } finally {
            // Windows 上 .git/objects 里的文件可能还被刚写完的句柄/索引器短暂锁住；
            // 清理失败不该让建应用失败（bare 仓库已推好），残留由系统临时目录清理兜底
            try { deleteRecursively(tmp); } catch (IOException e) { log.warn("清理模板临时目录失败（不影响已创建的仓库）：{}", tmp, e); }
        }
    }

    public Optional<CommitResult> head(String appId) throws IOException {
        try (Repository r = openBare(appId)) {
            ObjectId id = r.resolve(MAIN);
            if (id == null) return Optional.empty();
            try (RevWalk w = new RevWalk(r)) {
                RevCommit c = w.parseCommit(id);
                return Optional.of(new CommitResult(c.name(), c.getTree().name()));
            }
        }
    }

    public void deleteRepo(String appId) {
        try { deleteRecursively(repoPath(appId)); } catch (IOException ignored) { }
    }

    /**
     * 把仓库检出到工作区目录。commit 为空时检出 main；仓库为空时初始化一个空仓库。
     * 同时写 .git/info/exclude，避免把依赖和构建产物提交进去。
     */
    public void checkout(String appId, String commit, Path dir, String projectKind) throws Exception {
        Files.createDirectories(dir.getParent());
        if (head(appId).isEmpty()) {
            Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call().close();
        } else {
            try (Git g = Git.cloneRepository().setURI(repoPath(appId).toAbsolutePath().toString())
                    .setDirectory(dir.toFile()).setBranch("main").call()) {
                if (commit != null) g.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit).call();
            }
        }
        Path exclude = dir.resolve(".git/info/exclude");
        Files.createDirectories(exclude.getParent());
        String ex = "node_modules\n.zhiye/\n.DS_Store\n" + ("vite".equals(projectKind) ? "dist/\n" : "");
        Files.writeString(exclude, ex);
    }

    /** 在工作区提交全部改动，推到 bare 仓库，并在 main 上生成一次提交（父提交 = main 当前头）。 */
    public CommitResult commitWorkspace(String appId, Path dir, String message, String taskId) throws Exception {
        try (Git g = Git.open(dir.toFile())) {
            g.add().addFilepattern(".").call();
            g.add().setUpdate(true).addFilepattern(".").call();
            RevCommit c = g.commit().setAuthor(ident()).setCommitter(ident()).setMessage(message).setAllowEmpty(true).setSign(false).call();
            g.push().setRemote(repoPath(appId).toAbsolutePath().toString()).setForce(true)
                    .setRefSpecs(new RefSpec("+" + c.name() + ":refs/zhiye/tasks/" + taskId)).call();
            return commitTree(appId, c.getTree().name(), message);
        }
    }

    /** 用已有文件树在 main 上新建提交（恢复旧版本用）。 */
    public CommitResult commitTree(String appId, String treeId, String message) throws IOException {
        try (Repository r = openBare(appId); ObjectInserter ins = r.newObjectInserter()) {
            ObjectId head = r.resolve(MAIN);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(ObjectId.fromString(treeId));
            if (head != null) cb.setParentId(head);
            cb.setAuthor(ident());
            cb.setCommitter(ident());
            cb.setMessage(message);
            cb.setEncoding(java.nio.charset.StandardCharsets.UTF_8);
            ObjectId id = ins.insert(cb);
            ins.flush();
            RefUpdate ru = r.updateRef(MAIN);
            ru.setNewObjectId(id);
            ru.setExpectedOldObjectId(head == null ? ObjectId.zeroId() : head);
            RefUpdate.Result res = ru.update();
            if (res != RefUpdate.Result.NEW && res != RefUpdate.Result.FAST_FORWARD && res != RefUpdate.Result.FORCED)
                throw new IOException("更新 main 失败：" + res);
            return new CommitResult(id.name(), treeId);
        }
    }

    private RevCommit parse(Repository r, String commit) throws IOException {
        try (RevWalk w = new RevWalk(r)) { return w.parseCommit(ObjectId.fromString(commit)); }
    }

    public List<FileEntry> listFiles(String appId, String commit) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        try (Repository r = openBare(appId); TreeWalk tw = new TreeWalk(r)) {
            tw.addTree(parse(r, commit).getTree());
            tw.setRecursive(true);
            ObjectReader reader = r.newObjectReader();
            while (tw.next()) {
                if (tw.getFileMode(0) == FileMode.GITLINK) continue;
                out.add(new FileEntry(tw.getPathString(), reader.getObjectSize(tw.getObjectId(0), Constants.OBJ_BLOB)));
            }
        }
        return out;
    }

    // ---------- 版本改动（查看改动） ----------
    public record FileChange(String path, String status, String oldPath, int added, int removed, boolean binary) {}
    public record FileDiff(String path, String status, String diff, boolean truncated) {}

    private static boolean internal(String p) {
        return p != null && (p.startsWith(".zhiye/") || p.equals(".zhiye") || p.startsWith("node_modules/"));
    }

    private org.eclipse.jgit.diff.DiffFormatter formatter(Repository r, OutputStream out) {
        var f = new org.eclipse.jgit.diff.DiffFormatter(out);
        f.setRepository(r);
        f.setDiffComparator(org.eclipse.jgit.diff.RawTextComparator.DEFAULT);
        f.setDetectRenames(true);
        return f;
    }

    private List<org.eclipse.jgit.diff.DiffEntry> entries(Repository r, org.eclipse.jgit.diff.DiffFormatter f, String oldCommit, String newCommit) throws IOException {
        var oldTree = oldCommit == null ? null : parse(r, oldCommit).getTree();
        var newTree = parse(r, newCommit).getTree();
        List<org.eclipse.jgit.diff.DiffEntry> out = new ArrayList<>();
        for (var e : f.scan(oldTree, newTree)) {
            String p = e.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
            if (!internal(p)) out.add(e);
        }
        return out;
    }

    private static String status(org.eclipse.jgit.diff.DiffEntry e) {
        return switch (e.getChangeType()) {
            case ADD -> "A";
            case DELETE -> "D";
            case RENAME -> "R";
            case COPY -> "A";
            default -> "M";
        };
    }

    private static String pathOf(org.eclipse.jgit.diff.DiffEntry e) {
        return e.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
    }

    /** oldCommit 为 null 时与空树比较（第一个版本） */
    public List<FileChange> changes(String appId, String oldCommit, String newCommit) throws IOException {
        List<FileChange> out = new ArrayList<>();
        try (Repository r = openBare(appId); var f = formatter(r, org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
            for (var e : entries(r, f, oldCommit, newCommit)) {
                var header = f.toFileHeader(e);
                boolean binary = header.getPatchType() != org.eclipse.jgit.patch.FileHeader.PatchType.UNIFIED;
                int added = 0, removed = 0;
                for (var edit : header.toEditList()) {
                    added += edit.getLengthB();
                    removed += edit.getLengthA();
                }
                out.add(new FileChange(pathOf(e), status(e), e.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME ? e.getOldPath() : null,
                        binary ? 0 : added, binary ? 0 : removed, binary));
            }
        }
        out.sort(Comparator.comparing(FileChange::path));
        return out;
    }

    public Optional<FileDiff> fileDiff(String appId, String oldCommit, String newCommit, String path, int maxBytes) throws IOException {
        try (Repository r = openBare(appId)) {
            org.eclipse.jgit.diff.DiffEntry target;
            try (var scan = formatter(r, org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
                target = entries(r, scan, oldCommit, newCommit).stream()
                        .filter(e -> path.equals(pathOf(e)) || path.equals(e.getOldPath())).findFirst().orElse(null);
            }
            if (target == null) return Optional.empty();
            var buf = new java.io.ByteArrayOutputStream();
            var capped = new OutputStream() {
                boolean full;
                @Override public void write(int b) { if (buf.size() < maxBytes) buf.write(b); else full = true; }
                @Override public void write(byte[] b, int off, int len) {
                    int room = maxBytes - buf.size();
                    if (len > room) { full = true; len = Math.max(0, room); }
                    buf.write(b, off, len);
                }
            };
            try (var f = formatter(r, capped)) { f.format(target); }
            String diff = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(new FileDiff(pathOf(target), status(target), diff, capped.full));
        }
    }

    public Optional<byte[]> readFile(String appId, String commit, String path) throws IOException {
        if (path == null || path.isBlank()) return Optional.empty();
        String p = path.startsWith("/") ? path.substring(1) : path;
        try (Repository r = openBare(appId)) {
            try (TreeWalk tw = TreeWalk.forPath(r, p, parse(r, commit).getTree())) {
                if (tw == null || tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) return Optional.empty();
                return Optional.of(r.open(tw.getObjectId(0)).getBytes());
            }
        }
    }

    public boolean fileExists(String appId, String commit, String path) throws IOException {
        return readFile(appId, commit, path).isPresent();
    }

    public void exportZip(String appId, String commit, String rootName, OutputStream out) throws IOException {
        try (Repository r = openBare(appId); TreeWalk tw = new TreeWalk(r); ZipOutputStream zip = new ZipOutputStream(out)) {
            tw.addTree(parse(r, commit).getTree());
            tw.setRecursive(true);
            // rootName 为空时文件直接放在 zip 根（SkillHub 发布要求 SKILL.md 在根目录）
            String prefix = (rootName == null || rootName.isEmpty()) ? "" : rootName + "/";
            while (tw.next()) {
                if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) continue;
                zip.putNextEntry(new ZipEntry(prefix + tw.getPathString()));
                r.open(tw.getObjectId(0)).copyTo(zip);
                zip.closeEntry();
            }
        }
    }

    // ---------- 文件工具 ----------
    public static void copyTree(Path src, Path dst, Set<String> skipNames) throws IOException {
        try (Stream<Path> w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path rel = src.relativize(p);
                boolean skip = false;
                for (Path part : rel) if (skipNames.contains(part.toString())) { skip = true; break; }
                if (skip) continue;
                Path t = dst.resolve(rel.toString());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(t);
                else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) { deleteWithRetry(p); return; }
        try (Stream<Path> s = Files.list(p)) {
            for (Path c : s.toList()) deleteRecursively(c);
        }
        deleteWithRetry(p);
    }

    /** Windows 下文件可能被短暂锁住，JGit 对象也可能带只读属性；先清只读再重试，避免误伤主流程。 */
    private static void deleteWithRetry(Path p) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try { Files.deleteIfExists(p); return; }
            catch (IOException e) {
                last = e;
                if (e instanceof AccessDeniedException) {
                    try { Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS); }
                    catch (Exception ignored) {}
                }
                if (attempt < 5) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw last;
    }
}
