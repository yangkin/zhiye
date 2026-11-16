package com.tcl.csot.zhiye.sandbox;

public class RunnerException extends Exception {
    public RunnerException(String message) { super(message); }

    /** runner 回复 ok:false */
    public static class CommandFailed extends RunnerException {
        private final String code;
        public CommandFailed(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** runner 进程意外退出 / 超时 */
    public static class Died extends RunnerException {
        public Died(String message) { super(message); }
    }
}
