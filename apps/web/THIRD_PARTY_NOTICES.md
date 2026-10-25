# UI third-party notices

- **Dub UI** (`@dub/ui` 0.3.21): [Dub source](https://github.com/dubinc/dub), [AGPL-3.0 license](https://github.com/dubinc/dub/blob/main/LICENSE.md). Enterprise portions are commercially licensed; this app imports the published UI package and shared default theme, not enterprise source.
- **Dub default theme**: `src/dub-theme.css` includes the unchanged upstream light/dark color values and Tailwind v4 equivalents of the shared configuration. Source URLs and Git blob IDs are recorded in the file.
- **Resizable panel adapter**: adapted from shadcn/ui (MIT), backed by `react-resizable-panels`. Its retained license is in `src/components/ui/LICENSE.md`.
- **Sonner**: Dub's existing toast dependency is declared directly for the application's notifications (MIT).

The complete component provenance is recorded in `src/components/ui/source-provenance.json`.
