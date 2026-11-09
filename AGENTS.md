# AGENTS.md — Development Guidelines & Compliance Policy

This document defines strict operational rules and compliance requirements for AI coding agents (Claude, GPT, Gemini, Cursor, Antigravity, etc.) and human contributors working on `wetype-multilanguage`.

---

## 1. Zero Proprietary Code Policy (Anti-DMCA)

**CRITICAL REQUIREMENT:**
Under NO circumstance shall proprietary, copyrighted, or decompiled source code from Tencent / WeType or any third party be introduced into this repository.

### Strictly Prohibited
1. **No Decompiled Code**: Never paste, commit, or reconstruct decompiled Java, Kotlin, Smali, or C/C++ code extracted from host APKs.
2. **No Proprietary Assets or Binaries**: Never store, commit, or mirror proprietary host APKs (`*.apk`), Dalvik bytecodes (`*.dex`), Hermes bytecode bundles (`*.hbc`), or proprietary databases/leveldb tables in the repository.
3. **No Proprietary Copyrights or Signatures**: Do not include internal proprietary identifiers, author tags, internal comments, or proprietary reverse-engineering memos.
4. **No Internal Reverse-Engineering Documents**: Research notes containing decompiled source analysis, internal API structures, or anti-detection / signature-evasion details must NOT be stored in this repository.

---

## 2. Clean-Room Interoperability Standards

This project is an independent open-source enhancement module adhering strictly to **Clean-Room Interoperability principles**:

1. **Reflection & Dynamic Hooking**:
   - Interact with the host app strictly via standard Android Framework APIs, Xposed hooking contracts (`io.github.libxposed`), and dynamic reflection.
   - Do not import, stub, or duplicate proprietary host packages or class bodies into the compile-time classpath.
2. **Independent Engine Architecture**:
   - All input logic, character conversions (Romaji ↔ Kana), dictionary parsing, and candidate generation belong strictly to the independent `:common` module and the open-source Rime engine (`librime`).
   - The `:xposed` layer acts solely as a protocol adapter between standard Android input method lifecycle and the Rime engine.
3. **Decoupled Configuration**:
   - Member resolution must be handled through abstract role mappings and dynamic reflection, keeping the codebase completely clean and independent of host internals.

---

## 3. Open Source Licensing & Asset Integrity

1. **Open Source Dictionaries & Libraries**:
   - All bundled linguistic data (Mozc dictionaries, kana tables, Rime schemas) must originate from documented public-domain or permissive open-source repositories (e.g., BSD-3-Clause, Apache-2.0, MIT).
   - Original open-source licenses and attributions must be preserved in resource headers.
2. **Git Hygiene & Commit Safety**:
   - Never commit large binaries or binaries exceeding GitHub limits.
   - Ensure `.gitignore` explicitly filters out `apk/`, `*.apk`, `*.dex`, `build/`, `.gradle/`, and local scratch directories.
   - Commit messages must focus on functional features, bug fixes, and open-source engine enhancements without referencing internal reverse-engineering nomenclature.

---

## 4. Enforcement Checklist Before Any Push

Before creating or pushing any git commit, every agent must verify:
- [ ] No `*.apk` or decompiled source files are staged (`git status`).
- [ ] No internal reverse-engineering documentation files (`docs/wetype-*.md`) are in the repository.
- [ ] The `README.md` and codebase maintain clean, public-facing, professional terminology.
- [ ] All tests pass via `./gradlew :common:test`.
