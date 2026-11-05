# rexc promo

The README screenshots and the intro video, drawn in React and rendered with [Remotion](https://www.remotion.dev). Nothing here is a device capture.

The screens copy the app's Compose theme (`android/.../core/ui/theme/Theme.kt`) at Pixel 8 size, 412×915 dp. `src/theme.ts` holds the colors, `src/data.ts` the sessions and terminal text, and `src/screens/` one component per screen.

| Composition | Output |
|---|---|
| `home`, `terminal`, `alert`, `add-server` | 412×915 stills, rendered at 2× |
| `hero` | 1280×640 banner, rendered at 2× |
| `promo` | 1920×1080, 30 fps, 36.5 s |

## Preview

```sh
npm ci
npm run dev
```

## Render

```sh
for s in home terminal alert add-server hero; do npx remotion still $s out/$s.png --scale=2; done
npx remotion render promo out/promo.mp4 --crf=20
ffmpeg -i out/promo.mp4 -vf "fps=12,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" out/promo.gif
```

Then copy the PNGs to `docs/media/`, `out/promo.mp4` to `docs/media/rexc-intro.mp4`, and `out/promo.gif` to `docs/media/rexc-intro.gif`.
