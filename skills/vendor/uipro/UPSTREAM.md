# UI/UX Pro Max vendor bundle

- Source: <https://github.com/nextlevelbuilder/ui-ux-pro-max-skill>
- Installer package: `ui-ux-pro-max-cli`
- Installer version: `2.15.0`
- Install command used: `uipro init --ai codex`
- License: MIT; see `LICENSE`.

The vendored bundle contains these skills:

- `banner-design`
- `brand`
- `design`
- `design-system`
- `slides`
- `ui-styling`
- `ui-ux-pro-max`

The canonical files live under this directory. `.agents/skills/` and `.claude/skills/` contain
relative symlinks so Codex and Claude consume the same tracked content without duplication.

Repository compatibility changes are intentionally minimal: unsupported `argument-hint` keys were
removed from six `SKILL.md` frontmatter blocks. Nested license files from the installer are retained;
in particular, `ui-styling/LICENSE.txt` remains Apache-2.0.

The `2.15.0` installer includes two maintainer test modules that reference three scripts omitted from
the distributed bundle: `refresh-google-fonts.py`, `refresh-icon-catalog.py`, and
`evaluate-relevance.py`. Those two modules cannot be collected from the installed artifact. The data
validator and the other eight self-contained modules (130 tests) are the portable verification set.

Do not run `uipro init` directly in the repository when updating. It replaces the symlinks with
generated directories. Instead, install the desired CLI version in a temporary directory, review the
diff, replace the corresponding directories under `skills/vendor/uipro/`, preserve the upstream
license, regenerate `MANIFEST.sha256`, and run `make harness-check` plus the skill validators.
