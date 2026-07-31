package run.endive.cm.abi;

/**
 * The string encoding chosen at {@code canon lift}/{@code canon lower} definition time
 * (the {@code string-encoding=...} canonopt), mirroring the Python reference's {@code
 * cx.opts.string_encoding}.
 */
public enum StringEncoding {
    UTF8,
    UTF16,
    LATIN1_UTF16
}
