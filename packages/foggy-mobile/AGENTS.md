# Foggy Mobile Guidance

- Treat `package.json`, `src/manifest.json`, the current uni-app source, and module-local postmortems as authoritative. Historical skill page/component inventories are not contracts.
- Keep the uni-app dependency set compatible as a unit. Do not independently change the pinned Vue 3.4.21 packages, Pinia 2.1.7, Vite 5.x, or DCloud packages without validating H5 and the affected native target.
- Do not add `"type": "module"` or ESM-only build dependencies unless the current DCloud toolchain has been verified with them.
- App-Plus is not a browser environment. Preserve the custom `uni.request` adapter and early polyfill strategy; audit new dependencies for unconditional `window`, `self`, DOM, `FormData`, `Blob`, `fetch`, or XHR access.
- Keep `src/manifest.json` on Vue 3 unless an intentional migration updates the whole build chain. Use uni-app navigation, storage, lifecycle, and conditional-compilation APIs for cross-platform behavior.
- Changes to shared chat behavior may require coordinated updates and tests in `packages/foggy-chat-core`; rebuild the shared package before diagnosing stale mobile output.
- At minimum run `pnpm --filter @foggy/mobile type-check`, `pnpm --filter @foggy/mobile test`, and the relevant mobile build. Repository-wide frontend changes still require `bash scripts/build-frontend.sh`.
