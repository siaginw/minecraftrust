fn main() {
    // The pinned rpmalloc C source references token-privilege helpers even with its
    // default large-page configuration disabled. Its sys build omits this MSVC import.
    if std::env::var_os("CARGO_FEATURE_RP").is_some()
        && std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows")
    {
        println!("cargo:rustc-link-lib=advapi32");
    }
}
