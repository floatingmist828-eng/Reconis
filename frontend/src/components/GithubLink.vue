<script setup lang="ts">
withDefaults(defineProps<{ label?: boolean }>(), { label: false });
</script>

<template>
  <a class="github-link" href="https://github.com/floatingmist828-eng/Reconis" target="_blank" rel="noopener noreferrer" aria-label="GitHub 项目仓库（在新标签页打开）" title="GitHub 项目仓库 · 新标签页">
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 .9a11.1 11.1 0 0 0-3.51 21.63c.56.1.76-.24.76-.54v-2.12c-3.1.67-3.75-1.32-3.75-1.32-.5-1.28-1.24-1.62-1.24-1.62-1.02-.7.08-.69.08-.69 1.12.08 1.71 1.15 1.71 1.15 1 1.7 2.61 1.21 3.25.92.1-.72.39-1.21.71-1.49-2.47-.28-5.07-1.23-5.07-5.49 0-1.21.43-2.2 1.14-2.98-.11-.28-.5-1.41.11-2.94 0 0 .93-.3 3.05 1.14a10.58 10.58 0 0 1 5.55 0c2.12-1.44 3.05-1.14 3.05-1.14.61 1.53.22 2.66.11 2.94.71.78 1.14 1.77 1.14 2.98 0 4.27-2.6 5.21-5.08 5.49.4.35.75 1.02.75 2.06v3.11c0 .3.2.65.77.54A11.1 11.1 0 0 0 12 .9Z"/></svg>
    <span v-if="label">GitHub</span>
  </a>
</template>

<style scoped>
.github-link { display: inline-flex; align-items: center; justify-content: center; gap: 9px; min-width: 38px; min-height: 38px; padding: 8px; border-radius: 8px; color: inherit; font-size: 12px; transition: background-color .16s, color .16s; }
.github-link svg { width: 19px; height: 19px; flex: none; }
.github-link:hover, .github-link:focus-visible { background: #8ba7db20; color: var(--brand, #2959d9); }
@media (prefers-reduced-motion: reduce) { .github-link { transition: none; } }
</style>
