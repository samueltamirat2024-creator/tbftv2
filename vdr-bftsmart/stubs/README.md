# BFT-SMaRt API stubs — type-checking only, never on the runtime classpath

These files declare the small slice of the BFT-SMaRt 1.2 API that `vdr-bftsmart` uses, so the
module can be compiled and reviewed on a machine that cannot reach Maven Central. They contain no
logic: every method throws.

`build.sh` compiles against the real jar when it is present and falls back to these stubs
otherwise, printing which it used. A stub build proves the module is syntactically and
structurally sound; it proves nothing about the real engine, and the stubs are never put on the
runtime classpath — `build.sh` refuses to run a replica or a benchmark from a stub build.

If a stub signature and the real jar disagree, the real jar wins: fix the stub, not the caller.
