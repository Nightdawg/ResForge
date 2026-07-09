package resforge.gui;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Shows the <em>modern</em> Windows file open/save dialog — the Explorer-style
 * "Common Item Dialog" (Vista+) with the editable breadcrumb <b>address bar</b>,
 * so a folder or file path can be pasted straight in. Java's own
 * {@link java.awt.FileDialog} only reaches the legacy Win32 picker, so we drive
 * the COM {@code IFileOpenDialog}/{@code IFileSaveDialog} interfaces directly
 * through JNA.
 *
 * <p>This is Windows-only. Every entry point returns a {@link Result} that tells
 * the caller whether the native dialog was actually usable; on any failure (wrong
 * OS, missing JNA, a COM error) it reports {@link Result#unavailable()} so the
 * caller can fall back to {@link java.awt.FileDialog} without a crash.
 */
final class WinFileDialogs {
    private WinFileDialogs() {
    }

    /** A single file-type row for the dialog's type dropdown. */
    static final class Filter {
        final String name; // e.g. "Haven resource (*.res)"
        final String spec; // e.g. "*.res" or "*.png;*.jpg"

        Filter(String name, String spec) {
            this.name = name;
            this.spec = spec;
        }
    }

    /**
     * Outcome of showing a dialog. {@code available == false} means the native
     * dialog could not be used and the caller should fall back; when
     * {@code available == true}, a {@code null} {@link #path} means the user
     * cancelled.
     */
    static final class Result {
        final boolean available;
        final Path path;

        private Result(boolean available, Path path) {
            this.available = available;
            this.path = path;
        }

        static Result unavailable() {
            return new Result(false, null);
        }

        static Result cancelled() {
            return new Result(true, null);
        }

        static Result chosen(Path p) {
            return new Result(true, p);
        }
    }

    /** Shows the modern "open" dialog. */
    static Result open(long ownerHwnd, String title, String initialDir, String fileName, List<Filter> filters) {
        return run(false, ownerHwnd, title, initialDir, fileName, filters);
    }

    /** Shows the modern "save" dialog (with the built-in overwrite prompt). */
    static Result save(long ownerHwnd, String title, String initialDir, String fileName, List<Filter> filters) {
        return run(true, ownerHwnd, title, initialDir, fileName, filters);
    }

    /* --------------------------------------------------------- COM plumbing */

    private interface Ole32 extends Library {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);

        int CoInitializeEx(Pointer reserved, int coInit);

        void CoUninitialize();

        int CoCreateInstance(GUID.ByReference clsid, Pointer outer, int clsContext,
                GUID.ByReference iid, PointerByReference ppv);

        void CoTaskMemFree(Pointer pv);
    }

    private interface Shell32 extends Library {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);

        int SHCreateItemFromParsingName(WString path, Pointer bindCtx,
                GUID.ByReference iid, PointerByReference ppv);
    }

    // Two LPCWSTR pointers per COMDLG_FILTERSPEC entry.
    public static final class FilterSpec extends Structure {
        public WString pszName;
        public WString pszSpec;

        public FilterSpec() {
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("pszName", "pszSpec");
        }
    }

    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int SIGDN_FILESYSPATH = 0x80058000;

    private static final int FOS_OVERWRITEPROMPT = 0x00000002;
    private static final int FOS_FORCEFILESYSTEM = 0x00000040;
    private static final int FOS_PATHMUSTEXIST = 0x00000800;
    private static final int FOS_FILEMUSTEXIST = 0x00001000;

    private static final int S_OK = 0;
    private static final int S_FALSE = 1;

    private static final GUID.ByReference CLSID_FileOpenDialog =
            ref("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}");
    private static final GUID.ByReference CLSID_FileSaveDialog =
            ref("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}");
    private static final GUID.ByReference IID_IFileOpenDialog =
            ref("{D57C7288-D4AD-4768-BE02-9D969532D960}");
    private static final GUID.ByReference IID_IFileSaveDialog =
            ref("{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}");
    private static final GUID.ByReference IID_IShellItem =
            ref("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");

    private static GUID.ByReference ref(String guid) {
        return new GUID.ByReference(GUID.fromString(guid));
    }

    // IFileDialog vtable slots (IUnknown 0-2, IModalWindow 3, IFileDialog 4+).
    private static final int VT_RELEASE = 2;
    private static final int VT_SHOW = 3;
    private static final int VT_SET_FILETYPES = 4;
    private static final int VT_SET_OPTIONS = 9;
    private static final int VT_GET_OPTIONS = 10;
    private static final int VT_SET_FOLDER = 12;
    private static final int VT_SET_FILENAME = 15;
    private static final int VT_SET_TITLE = 17;
    private static final int VT_GET_RESULT = 20;
    // IShellItem::GetDisplayName is vtable slot 5.
    private static final int VT_GET_DISPLAY_NAME = 5;

    /**
     * Runs the whole dialog on a dedicated single-threaded-apartment (STA) thread
     * — required by the COM file dialog — and blocks until it closes. Any failure
     * degrades to {@link Result#unavailable()}.
     */
    private static Result run(boolean save, long ownerHwnd, String title,
            String initialDir, String fileName, List<Filter> filters) {
        final Result[] box = {Result.unavailable()};
        Thread t = new Thread(() -> box[0] = runOnStaThread(
                save, ownerHwnd, title, initialDir, fileName, filters), "win-file-dialog");
        t.start();
        try {
            t.join();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unavailable();
        }
        return box[0];
    }

    private static Result runOnStaThread(boolean save, long ownerHwnd, String title,
            String initialDir, String fileName, List<Filter> filters) {
        boolean comInit = false;
        Pointer dialog = null;
        try {
            int hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED);
            if(hr != S_OK && hr != S_FALSE)
                return Result.unavailable();
            comInit = true;

            PointerByReference ppv = new PointerByReference();
            hr = Ole32.INSTANCE.CoCreateInstance(
                    save ? CLSID_FileSaveDialog : CLSID_FileOpenDialog, Pointer.NULL,
                    CLSCTX_INPROC_SERVER, save ? IID_IFileSaveDialog : IID_IFileOpenDialog, ppv);
            if(hr != S_OK || ppv.getValue() == null)
                return Result.unavailable();
            dialog = ppv.getValue();

            configure(dialog, save, title, initialDir, fileName, filters);

            hr = call(dialog, VT_SHOW, ptr(ownerHwnd));
            if(hr != S_OK) // includes the user-cancelled HRESULT
                return Result.cancelled();

            Path chosen = readResult(dialog);
            return chosen != null ? Result.chosen(chosen) : Result.cancelled();
        } catch(Throwable ignore) {
            return Result.unavailable();
        } finally {
            release(dialog);
            if(comInit)
                try {
                    Ole32.INSTANCE.CoUninitialize();
                } catch(Throwable ignore) {
                    // best-effort teardown
                }
        }
    }

    private static void configure(Pointer dialog, boolean save, String title,
            String initialDir, String fileName, List<Filter> filters) {
        IntByReference opts = new IntByReference();
        call(dialog, VT_GET_OPTIONS, opts);
        int flags = opts.getValue() | FOS_FORCEFILESYSTEM
                | (save ? FOS_OVERWRITEPROMPT | FOS_PATHMUSTEXIST : FOS_FILEMUSTEXIST);
        call(dialog, VT_SET_OPTIONS, flags);

        if(title != null)
            call(dialog, VT_SET_TITLE, new WString(title));

        FilterSpec[] specs = buildFilters(filters);
        if(specs != null)
            call(dialog, VT_SET_FILETYPES, specs.length, specs[0].getPointer());

        setFolder(dialog, initialDir);

        if(fileName != null && !fileName.isEmpty())
            call(dialog, VT_SET_FILENAME, new WString(fileName));
    }

    /** Builds a contiguous COMDLG_FILTERSPEC array, or {@code null} if no filters. */
    private static FilterSpec[] buildFilters(List<Filter> filters) {
        if(filters == null || filters.isEmpty())
            return null;
        FilterSpec[] specs = (FilterSpec[]) new FilterSpec().toArray(filters.size());
        for(int i = 0; i < filters.size(); i++) {
            specs[i].pszName = new WString(filters.get(i).name);
            specs[i].pszSpec = new WString(filters.get(i).spec);
            specs[i].write();
        }
        return specs;
    }

    private static void setFolder(Pointer dialog, String initialDir) {
        if(initialDir == null || initialDir.isEmpty())
            return;
        PointerByReference item = new PointerByReference();
        int hr = Shell32.INSTANCE.SHCreateItemFromParsingName(
                new WString(initialDir), Pointer.NULL, IID_IShellItem, item);
        if(hr != S_OK || item.getValue() == null)
            return;
        Pointer shellItem = item.getValue();
        try {
            call(dialog, VT_SET_FOLDER, shellItem);
        } finally {
            release(shellItem);
        }
    }

    private static Path readResult(Pointer dialog) {
        PointerByReference result = new PointerByReference();
        if(call(dialog, VT_GET_RESULT, result) != S_OK || result.getValue() == null)
            return null;
        Pointer shellItem = result.getValue();
        try {
            PointerByReference name = new PointerByReference();
            if(call(shellItem, VT_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) != S_OK
                    || name.getValue() == null)
                return null;
            Pointer str = name.getValue();
            try {
                String path = str.getWideString(0);
                return (path != null && !path.isEmpty()) ? Path.of(path) : null;
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(str);
            }
        } finally {
            release(shellItem);
        }
    }

    /* ------------------------------------------------- raw vtable invocation */

    private static Function vtbl(Pointer comObject, int slot) {
        Pointer vtable = comObject.getPointer(0);
        Pointer method = vtable.getPointer((long) slot * Native.POINTER_SIZE);
        return Function.getFunction(method, Function.ALT_CONVENTION);
    }

    /** Invokes a COM method: the interface pointer is passed as the implicit {@code this}. */
    private static int call(Pointer comObject, int slot, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = comObject;
        System.arraycopy(args, 0, all, 1, args.length);
        return vtbl(comObject, slot).invokeInt(all);
    }

    private static void release(Pointer comObject) {
        if(comObject != null)
            try {
                vtbl(comObject, VT_RELEASE).invokeInt(new Object[]{comObject});
            } catch(Throwable ignore) {
                // best-effort release
            }
    }

    private static Pointer ptr(long handle) {
        return handle == 0 ? Pointer.NULL : new Pointer(handle);
    }
}
