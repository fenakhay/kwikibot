# The kwikibot logo

The W and the kwikibot wordmark. The logos and icons are SVGs, with PNGs at four times their size:
936 × 192 for a logo and 192 × 192 for an icon. The avatars are 1024 px squares, and the social
preview is a PNG only.

In the logos, Dark and Light name the colour of the lettering, not of the background: `logo-dark`
has black lettering, for light backgrounds, and `logo-light` has white lettering, for dark ones.
The mono logos are all in that one colour, W included, for places that print a single colour.
The avatars have no lettering, so there dark and light name the background.

| File                                  | What it shows                       | Where it goes                                     |
|---------------------------------------|-------------------------------------|---------------------------------------------------|
| `logo-dark.svg`, `.png`               | The gradient W, black lettering     | The README headers on a light theme, and on npm   |
| `logo-light.svg`, `.png`              | The gradient W, white lettering     | The README headers on a dark theme                |
| `logo-icon.svg`, `.png`               | The gradient W alone                | Dokka's header and tab icon                       |
| `logo-mono-dark.svg`, `.png`          | The W and lettering in black        | One-colour print on light backgrounds             |
| `logo-mono-light.svg`, `.png`         | The W and lettering in white        | One-colour print on dark backgrounds              |
| `logo-icon-mono-dark.svg`, `.png`     | The W alone in black                | One-colour print on light backgrounds             |
| `logo-icon-mono-light.svg`, `.png`    | The W alone in white                | One-colour print on dark backgrounds              |
| `avatar-dark.svg`, `.png`             | The gradient W on black             | Profile pictures                                  |
| `avatar-light.svg`, `.png`            | The gradient W on white             | Profile pictures                                  |
| `avatar-palette.svg`, `.png`          | A white W on the gradient           | Profile pictures                                  |
| `social-preview.png`                  | The logo on black, 1280 × 640       | GitHub's repository card                          |

The avatars keep the W inside the circle that sites crop profile pictures to. GitHub has no API
for the social preview: it is uploaded under the repository's Settings → General → Social preview.

The root `README.md` and `kwikibot-js/README.md` show `logo-dark.svg` and `logo-light.svg` in a
`<picture>`, and the Dokka convention plugin in `build-logic` gives `logo-icon.svg` to Dokka, which
uses that file name for its own logo. npm has no dark theme and shows only the `<img>`, so the npm
README reads `logo-dark.svg` from `main` on GitHub, as do the copies already published. Renaming
any of the three means changing those references, and breaks the logo on published versions.
