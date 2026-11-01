# Contributing

Issues and pull requests are welcome.

1. Run `./gradlew test build` and make sure it reports `BUILD SUCCESSFUL`.
2. Add a test for any behaviour change. Terminal behaviour is defined by what
   `@xterm/headless` 5.5.0 with `@xterm/addon-unicode11` 0.8.0 actually does, so check the real package rather than relying
   on memory, and record the probe in the test.
3. Keep runtime dependencies to `kotlin-stdlib` only.
4. Do not "fix" xterm quirks in `Reflow`: they are bug-compatible on purpose.
5. If you port code from another project, make sure its license is compatible with MIT
   and update `THIRD_PARTY_NOTICES.md`.

By contributing, you agree that your contribution is licensed under the MIT License.
Comments and documentation are written in English.
