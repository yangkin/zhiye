import { lazy, Suspense, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { LightLayout } from './components/LightLayout';
import { Toasts } from './components/Toasts';
import { CodeImportDialog } from './import/CodeImportDialog';
import { DataSourceDialog } from './components/DataSourceDialog';
import { CreatePage } from './pages/CreatePage';
import { AuthGate, LoginPage, SsoCallbackPage } from './pages/LoginPage';
import { RecoverPage } from './pages/RecoverPage';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ConfirmHost } from './components/ConfirmDialog';

const AppsPage = lazy(() => import('./pages/AppsPage').then((m) => ({ default: m.AppsPage })));
const ChatPage = lazy(() => import('./pages/ChatPage').then((m) => ({ default: m.ChatPage })));
const HistoryPage = lazy(() => import('./pages/HistoryPage').then((m) => ({ default: m.HistoryPage })));
const CommunityPage = lazy(() => import('./pages/CommunityPage').then((m) => ({ default: m.CommunityPage })));
const WorkPage = lazy(() => import('./pages/WorkPage').then((m) => ({ default: m.WorkPage })));
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })));
const LogsPage = lazy(() => import('./pages/LogsPage').then((m) => ({ default: m.LogsPage })));
const WorkbenchRoute = lazy(() => import('./workbench/Workbench').then((m) => ({ default: m.WorkbenchRoute })));

function RouteFallback() {
  return <div style={{ padding: '48px 24px', textAlign: 'center', color: 'var(--text-muted, #888)', fontSize: 13 }}>正在加载页面…</div>;
}

export function App() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        navigate('/create');
        setTimeout(() => document.getElementById('home-prompt')?.focus(), 30);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navigate]);

  return (
    <AuthGate>
      <ErrorBoundary area="这个页面" page key={location.pathname}>
      <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route path="/" element={<Navigate to="/create" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/access_token" element={<SsoCallbackPage />} />
        <Route path="/recover" element={<RecoverPage />} />
        <Route path="/apps/:id" element={<WorkbenchRoute />} />
        <Route element={<LightLayout />}>
          <Route path="/create" element={<CreatePage />} />
          <Route path="/apps" element={<AppsPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/community" element={<CommunityPage />} />
          <Route path="/community/:id" element={<WorkPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/chat/:id" element={<ChatPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/create" replace />} />
      </Routes>
      </Suspense>
      </ErrorBoundary>
      <CodeImportDialog />
      <DataSourceDialog />
      <ConfirmHost />
      <Toasts />
    </AuthGate>
  );
}
