// skipping-rules.js — Phase 2.4, обновляется через манифест без пересборки APK
// Селекторы хрупкие (YouTube меняет классы) — править здесь и перевыпускать манифест
window.__votSkippingRules = {
  css: "ytd-ad-slot, ytd-rich-item-renderer:has(ytd-ad-slot), #player-ads, #masthead-ad { display: none !important; }",
  skipSelectors: [".ytp-ad-skip-button-modern", ".ytp-ad-skip-button", ".ytp-skip-ad-button", ".videoAdUiSkipButton"],
  adOverlaySelector: ".ytp-ad-player-overlay, .ad-showing",
  adShowingClass: "ad-showing"
};
