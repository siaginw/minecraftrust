"""H17 bounded build tree runner, derived from the reviewed H9 job helper.
Local extension: total Job memory/process/output bounds and descendant quiescence.
Original tools/nbt-region-experiment/job.py remains unchanged.
"""
import ctypes as c
from ctypes import wintypes as w
import subprocess
import os
import time

class Basic(c.Structure):
    _fields_ = [("process_time", c.c_longlong), ("job_time", c.c_longlong),
                ("flags", w.DWORD), ("min_working_set", c.c_size_t), ("max_working_set", c.c_size_t),
                ("active_processes", w.DWORD), ("affinity", c.c_size_t),
                ("priority", w.DWORD), ("scheduling", w.DWORD)]

class Io(c.Structure):
    _fields_ = [(name, c.c_ulonglong) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]

class Extended(c.Structure):
    _fields_ = [("basic", Basic), ("io", Io), ("process_memory", c.c_size_t),
                ("job_memory", c.c_size_t), ("peak_process", c.c_size_t), ("peak_job", c.c_size_t)]

class Accounting(c.Structure):
    _fields_ = [("user", c.c_longlong), ("kernel", c.c_longlong), ("period_user", c.c_longlong), ("period_kernel", c.c_longlong), ("faults", w.DWORD), ("total_processes", w.DWORD), ("active_processes", w.DWORD), ("terminated_processes", w.DWORD)]

class LaunchFailure(RuntimeError):
    def __init__(self, reason, pid, code, stdout, stderr, timed_out, peak, terminated):
        super().__init__(reason)
        self.pid, self.code = pid, code
        self.stdout, self.stderr = stdout, stderr
        self.timed_out, self.peak, self.terminated = timed_out, peak, terminated


def launch(argv, *, cwd, env, memory_mib=3072, timeout=240, startup_fault=None, output_limit=4*1024*1024):
    if not 1 <= memory_mib <= 3072 or not 0 < timeout <= 240 or not 1 <= output_limit <= 4*1024*1024:
        raise ValueError("build limits")
    if startup_fault not in (None, "assignment-failure", "resume-failure"):
        raise ValueError("unknown startup fault")
    kernel = c.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [c.c_void_p, w.LPCWSTR]
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.SetInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD]
    kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
    kernel.QueryInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.c_void_p]
    kernel.TerminateJobObject.argtypes = [w.HANDLE, w.UINT]
    kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
    kernel.WaitForSingleObject.restype = w.DWORD
    kernel.CloseHandle.argtypes = [w.HANDLE]
    ntdll = c.WinDLL("ntdll")
    ntdll.NtResumeProcess.argtypes = [w.HANDLE]
    ntdll.NtResumeProcess.restype = c.c_long
    handle = kernel.CreateJobObjectW(None, None)
    if not handle:
        raise c.WinError(c.get_last_error())
    child = None
    assigned = False
    buffers = {"stdout": bytearray(), "stderr": bytearray()}
    timed_out = False
    peak = None
    failure = None
    terminated = True
    try:
        limits = Extended()
        limits.basic.flags = 0x200 | 0x2000 | 0x8 # total Job memory, kill on close, active process limit
        limits.basic.active_processes = 64
        limits.job_memory = memory_mib * 1024 * 1024
        if not kernel.SetInformationJobObject(handle, 9, c.byref(limits), c.sizeof(limits)):
            raise c.WinError(c.get_last_error())
        child = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=0x4, shell=False)
        terminated = False
        if startup_fault == "assignment-failure":
            raise RuntimeError("injected assignment failure before Job ownership")
        if not kernel.AssignProcessToJobObject(handle, w.HANDLE(int(child._handle))):
            raise c.WinError(c.get_last_error())
        assigned = True
        if startup_fault == "resume-failure":
            raise RuntimeError("injected resume failure after Job ownership")
        if ntdll.NtResumeProcess(w.HANDLE(int(child._handle))) != 0:
            raise RuntimeError("cannot resume limited child")
        started = time.monotonic()
        streams = {"stdout": child.stdout, "stderr": child.stderr}
        active = set(streams)
        for stream in streams.values(): os.set_blocking(stream.fileno(), False)
        while active or child.poll() is None:
            progressed = False
            for key in tuple(active):
                try: data = os.read(streams[key].fileno(), 4096)
                except BlockingIOError: continue
                if not data: active.remove(key); continue
                progressed = True
                remaining = output_limit-len(buffers[key])
                buffers[key].extend(data[:remaining])
                if len(data) > remaining:
                    raise RuntimeError("result output limit")
            if time.monotonic()-started > timeout and not timed_out:
                timed_out = True
                kernel.TerminateJobObject(handle, 137)
            if timed_out and time.monotonic()-started > timeout+5:
                raise RuntimeError("limited child failed to terminate")
            if not progressed: time.sleep(.002)
        child.wait(timeout=5)
    except Exception as error:
        failure = type(error).__name__ + ": " + str(error)
    finally:
        try:
            if child is not None and child.poll() is None:
                # Before successful assignment, only direct child termination
                # can release the suspended process. Also backstop Job failure.
                if assigned:
                    kernel.TerminateJobObject(handle, 137)
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
            if child is not None:
                # poll()/wait() can return a cached exit code before Windows
                # signals final process teardown. Confirm the kernel handle.
                terminated = kernel.WaitForSingleObject(w.HANDLE(int(child._handle)), 5000) == 0
                if not terminated:
                    raise RuntimeError("child process handle did not signal termination")
            if assigned:
                observed=Extended()
                if not kernel.QueryInformationJobObject(handle,9,c.byref(observed),c.sizeof(observed),None):
                    raise c.WinError(c.get_last_error())
                peak = observed.peak_job
                accounting=Accounting()
                if not kernel.QueryInformationJobObject(handle,1,c.byref(accounting),c.sizeof(accounting),None):
                    raise c.WinError(c.get_last_error())
                if accounting.active_processes:
                    failure = (failure + "; " if failure else "") + "descendants alive after direct child exit"
                    kernel.TerminateJobObject(handle,137)
                    deadline=time.monotonic()+5
                    while accounting.active_processes and time.monotonic()<deadline:
                        time.sleep(.01)
                        if not kernel.QueryInformationJobObject(handle,1,c.byref(accounting),c.sizeof(accounting),None):
                            raise c.WinError(c.get_last_error())
                    if accounting.active_processes:
                        terminated=False
                        raise RuntimeError("build descendants failed to terminate")
        except Exception as error:
            failure = (failure + "; " if failure else "") + "cleanup: " + str(error)
        finally:
            if child is not None:
                for stream in (child.stdout, child.stderr):
                    if stream is not None: stream.close()
            kernel.CloseHandle(handle)
    if failure:
        raise LaunchFailure(failure, child.pid if child else None,
            child.returncode if child else None, bytes(buffers["stdout"]),
            bytes(buffers["stderr"]), timed_out, peak, terminated)
    return child.returncode, bytes(buffers["stdout"]), bytes(buffers["stderr"]), timed_out, peak
