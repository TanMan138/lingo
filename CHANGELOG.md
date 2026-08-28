# Changelog

All notable changes to Lingo. Versions follow [semantic versioning](https://semver.org/).

## [1.1.7]

### Fixed

- Romanized chat (Hindi, Russian, and similar languages typed in Latin letters
  instead of their native script) often failed to translate — the on-device
  language detector either missed it entirely or, worse, confidently guessed the
  wrong Latin-script language (Dutch, Swedish, etc.) and translated from that
  instead. Cloud backends (DeepL, Google, Langbly, Ollama) now fall back to their
  own auto-detect for these cases rather than trusting a bad local guess.

## [1.1.6]

### Fixed

- Chat on servers that put their own hover or click on a whole message line (most
  minigame servers, e.g. BedWars global chat) could not be translated at all —
  hovering showed the server's tooltip and nothing else, because the server's own
  style hid Lingo's. Every translatable line now ends with a small grey `⇄` handle:
  hover or click that to translate. The server's own name clicks keep working.
- The sender decoration was being translated along with the message. A line like
  `[Global] [GOLD] [detemo head]detemo → текст` sent the channel, rank, and name to
  the translator, which pushed language detection towards English on short messages
  and, on a paid service, billed characters for text nobody reads.

## [1.1.5]

### Added

- **Spending limit for online services.** Google and Langbly both include 500,000
  characters a month and then bill your card automatically — neither stops on its
  own. Lingo now counts what it sends and, at the budget in Config (500,000 by
  default, 0 to disable), goes back to translating on your computer for the rest of
  the month. Chat keeps working; you stop paying. Warnings at 80% and 95%.
- `/translate usage` — how many characters have gone to a paid service this month.
  It counts only what this mod sent, so your provider's dashboard remains the real
  number.

### Fixed

- DeepL guidance said to sign up for a free tier. DeepL closed its free monthly API
  plan to new signups; the README, guide, and config screen now say plainly that
  DeepL costs money.
- DeepL's free and paid endpoints are now chosen from the key itself — older API
  Free keys end in `:fx` — instead of relying on a checkbox that was easy to leave
  wrong and produced only an authorization error when it was.
- Google and Langbly were described as having a "free tier" with no mention that
  both charge automatically once the included allowance is gone.

### Changed

- The download is 31 MB smaller (257 MB → 224 MB). The bundled ONNX Runtime shipped
  macOS debugger symbols that nothing reads at runtime; they are now stripped from
  the jar. Native libraries for all five supported platforms are unchanged.

## [1.1.3]

### Changed

- Releases are now published to CurseForge and Modrinth automatically from the
  same tag that builds the jar. No player-facing changes.

## [1.1.2]

### Changed

- The mod is now called **Lingo** everywhere a player sees it: the mod list entry,
  the `[Lingo]` chat prefix, the guide, and the settings screen. The mod id, config
  file, and saved language packs are untouched, so nothing needs reconfiguring.
- New mod icon.

## [1.1.1]

### Fixed

- On-device translation could fail with `ServiceConfigurationError: HfZooProvider not
  a subtype`, after which every later translation reported
  `Could not initialize class ai.djl.repository.zoo.DefaultModelZoo` until the game
  was restarted. DJL discovers its engines through the thread context classloader,
  which our inference threads did not carry; model loading now installs it explicitly.

## [1.1.0]

### Added

- **Automatic chat reading.** Foreign chat now shows its English on the same line
  (`<Alice> bonjour → hello`) without hovering. Turn it off with
  `/translate read hover`, or in Config → What you read.
- `/translate download <code>` — save a language ahead of time so the first foreign
  message translates instantly.
- `/translate backend <ondevice|deepl|google|langbly|ollama>` — switch translation
  method without a config screen.
- `/translate read auto|hover` — choose how incoming chat is handled.
- Translation cache: repeated lines are not translated twice, which also stops
  repeat charges on DeepL / Google / Langbly keys.

### Changed

- **Yet Another Config Lib is now optional.** The mod loads and works on its own;
  install YACL (and Mod Menu) only if you want the settings screen. Everything it
  configures is reachable through `/translate` commands.
- Auto reading never starts a download by itself — an unsaved language still waits
  for a hover or `/translate download`, so joining a server cannot trigger a
  surprise ~100 MB fetch.
- Join/leave lines, advancement announcements, your own messages, and lines too
  short to identify are left alone instead of being hooked for translation.
- On-device model downloads no longer time out after 60 seconds — a slow but healthy
  download used to be reported as a failure and could disable a language for the
  session.

## [1.0.0]

- First release: hover-to-read incoming chat, optional outgoing translation,
  on-device OPUS-MT models, BYOK DeepL / Google / Langbly, self-hosted Ollama,
  Latin romanization for AntiSpam-heavy servers, gaming slang protection.
