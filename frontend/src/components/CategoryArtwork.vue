<script setup lang="ts">
import { computed, useId } from "vue";
import { resolveArtworkKind } from "../utils/categoryArtwork";

const props = withDefaults(defineProps<{
  subject?: string;
  merchantName?: string;
  category?: string;
  size?: "sm" | "md" | "lg";
}>(), { size: "md" });
const kind = computed(() => resolveArtworkKind(props));
const id = useId();
const surface = `url(#${id}-surface)`;
const object = `url(#${id}-object)`;
</script>

<template>
  <span class="category-artwork" :class="[`category-artwork--${kind}`, `category-artwork--${size}`]" :data-artwork="kind" aria-hidden="true">
    <svg viewBox="0 0 80 80" fill="none" focusable="false" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient :id="`${id}-surface`" x1="22" y1="20" x2="57" y2="62" gradientUnits="userSpaceOnUse">
          <stop stop-color="#fff"/><stop offset="1" stop-color="#dce5e9"/>
        </linearGradient>
        <linearGradient :id="`${id}-object`" x1="26" y1="23" x2="58" y2="61" gradientUnits="userSpaceOnUse">
          <stop stop-color="var(--art-light)"/><stop offset="1" stop-color="var(--art-dark)"/>
        </linearGradient>
      </defs>
      <ellipse class="art-ground" cx="40" cy="62" rx="24" ry="4" fill="#294158" fill-opacity=".09"/>
      <g class="art-object" stroke-linecap="round" stroke-linejoin="round">
        <template v-if="kind === 'coffee'">
          <ellipse cx="38" cy="56" rx="24" ry="6" fill="#d8e1e5" stroke="#c7d2d7"/>
          <ellipse cx="38" cy="54" rx="23" ry="5" :fill="surface"/>
          <path d="M52 31h4c11 0 11 16 1 18h-6" stroke="#a6bac4" stroke-width="5"/>
          <path d="M53 31h3c9 0 9 14 0 15h-4" stroke="#edf3f3" stroke-width="3"/>
          <path d="M21 29h33l-3 19c-1 9-24 9-27 0Z" :fill="surface" stroke="#aebfc7"/>
          <path d="m25 34 2 12c.5 3 3 4 5 5" stroke="#fff" stroke-width="2"/>
          <ellipse cx="37.5" cy="29" rx="16.5" ry="5.5" fill="#f9fbfa" stroke="#aebfc7"/>
          <ellipse cx="37.5" cy="29.4" rx="13.4" ry="3.7" fill="#775542"/>
          <path d="M30 29c3-3 6-1 8 0 2-2 5-3 7-1-1 2-4 3-7 3s-6-1-8-2Z" fill="#d5b596"/>
          <path d="M32 20c-4-5 3-5 0-10M43 21c-4-5 4-6 1-10" stroke="#a7b4ba" stroke-width="1.5"/>
          <ellipse cx="60" cy="57" rx="4.2" ry="2.8" transform="rotate(-28 60 57)" fill="#866650"/>
          <path d="m58 59 4-4" stroke="#c1a186" stroke-width="1.2"/>
        </template>

        <template v-else-if="kind === 'tea'">
          <path d="m28 23 4 35c.5 5 20 5 21 0l4-35" :fill="surface" stroke="#a5bbba"/>
          <path d="m31 35 2.5 22c1 3 16 3 17 0L53 35Z" :fill="object"/>
          <ellipse cx="42" cy="35" rx="11" ry="3" fill="#abc4af"/>
          <path d="m35 39 2 15" stroke="#d4e5d4" stroke-width="2"/>
          <ellipse cx="42.5" cy="23" rx="15" ry="4.5" fill="#f6faf6" stroke="#a5bbba"/>
          <path d="m44 28 3-16 7-3" stroke="#75978e" stroke-width="3"/>
          <path d="M19 49c10-1 12 5 8 12-7-1-10-6-8-12Z" fill="#91ac91"/>
          <path d="m20 51 7 10" stroke="#d0dfc9"/>
          <path d="M24 41c9 1 12 7 9 13-7-2-10-7-9-13Z" fill="#b6c7a7"/>
          <path d="m26 44 6 9" stroke="#e0e9d5"/>
          <path d="M37 45h10" stroke="#edf3e8" stroke-width="2"/>
        </template>

        <template v-else-if="kind === 'meal'">
          <path d="M23 39h-5c-7 0-7 7 0 7h6M56 39h6c7 0 7 7 0 7h-6" stroke="#708f99" stroke-width="4"/>
          <path d="M21 36h39l-4 17c-2 9-28 9-31 0Z" :fill="object" stroke="#65858f"/>
          <path d="m27 42 2 9c1 3 4 4 7 4" stroke="#b2c9cb" stroke-width="1.5"/>
          <ellipse cx="40.5" cy="36" rx="19.5" ry="6.5" fill="#e4dbbd" stroke="#6d8c94"/>
          <path d="m31 34 4-3m10 3 3-2m-7 5 3 1m-18-3 3 2" stroke="#fff4d9" stroke-width="2.5"/>
          <path d="m33 38 3-1m13-1 3 1" stroke="#a98d5f" stroke-width="2.5"/>
          <path d="m39 33 3 1m-12 3 1-2" stroke="#7d9b73" stroke-width="2"/>
          <path d="M22 25c3-10 29-10 35 0Z" fill="#8eacb2" stroke="#6c8d95"/>
          <ellipse cx="39.5" cy="25" rx="17.5" ry="3" fill="#698992"/>
          <path d="M35 18v-3c0-4 9-4 9 0v3" fill="#698992"/>
          <path d="M28 22c4-3 10-4 14-3" stroke="#bcd1d1" stroke-width="1.5"/>
          <path d="m60 21-1 7m6-4-2 7" stroke="#bdc9c8" stroke-width="1.4"/>
        </template>

        <template v-else-if="kind === 'bakery'">
          <ellipse cx="40" cy="57" rx="26" ry="6" fill="#dcded5"/>
          <ellipse cx="40" cy="55" rx="26" ry="5" fill="#f8f7ef" stroke="#d8dbd2"/>
          <path d="M42 28c0-7 17-9 21-1l-1 20H42Z" fill="#e3c392" stroke="#bb9767"/>
          <path d="M46 29c0-4 9-5 12-1l-1 16H46Z" fill="#f5e4bc"/>
          <path d="M49 33h1m3 5h1m-5 4h1" stroke="#d2b381" stroke-width="1.5"/>
          <path d="M18 43c0-8 5-15 12-17 7-2 14 0 19 5 6 6 6 15 2 20l-9-4c1-5-2-8-6-8-5-1-8 4-8 8Z" :fill="object" stroke="#b88d56"/>
          <path d="M18 43c-3 3-3 8 1 10l9-6M51 51c4 2 8-1 7-5l-5-4" fill="#d2a466" stroke="#b88d56"/>
          <path d="M25 30c1 5 4 8 7 9m5-13c-1 4-1 8 0 13m8-10c-3 3-4 6-4 11" stroke="#f0d49f" stroke-width="3.5"/>
          <path d="M23 33c1 3 2 5 4 6M39 29v5" stroke="#f8e9c2" stroke-width="1.2"/>
          <path d="m29 57 1 1m15-1 2 1" stroke="#ba9664" stroke-width="1.5"/>
        </template>

        <template v-else-if="kind === 'print'">
          <path d="m25 24 29-4 9 36-31 6Z" fill="#b5c7d8" stroke="#9eafbf"/>
          <path d="m22 20 29-4 9 36-31 6Z" fill="#eef4f7" stroke="#bac9d4"/>
          <path d="m19 17 29-4 9 36-31 6Z" :fill="surface" stroke="#b5c5d0"/>
          <path d="m25 24 21-3 2 10-21 3Z" :fill="object"/>
          <path d="m29 31 4-5 4 3 3-3 5 4" stroke="#e6eff3" stroke-width="1.1"/>
          <path d="m29 40 19-3m-18 8 14-2" stroke="#9aafbd" stroke-width="1.7"/>
          <path d="m24 19-3-11c-1-5 6-6 7-1l4 16c1 5-6 6-7 2L22 15" stroke="#587c9b" stroke-width="2.4"/>
          <path d="m20 14 1 5" stroke="#d0e3ef" stroke-width=".9"/>
          <path d="m45 58 17-3" stroke="#f3f7f8" stroke-width="1.2"/>
        </template>

        <template v-else-if="kind === 'grocery'">
          <path d="m24 31 1-15 9-3 5 6-2 15" fill="#f1f3e7" stroke="#b2beaf"/>
          <path d="m25 16 9-3 5 6-10 3Z" fill="#bdceba"/>
          <path d="m29 22 8-2-1 9-7 2Z" fill="#8ea88e"/>
          <path d="M46 16h8v6l3 4v12H43V26l3-4Z" fill="#a7c0bc" stroke="#8aa7a5"/>
          <path d="M46 16h8v5h-8Z" fill="#789b9b"/>
          <path d="M46 28h8v8h-8Z" fill="#edf2e7"/>
          <path d="m19 31 42-2-3 31c-2 4-32 4-35 0Z" :fill="object" stroke="#899ba7"/>
          <path d="m19 31 7 6 35-8" stroke="#cfdbdd" stroke-width="1.3"/>
          <path d="m26 37 2 22" stroke="#b9cbce" stroke-width="1.2"/>
          <path d="M34 34c-2 15 14 14 14-1" stroke="#eef3ed" stroke-width="2.8"/>
          <path d="M39 53c-3-5 1-9 6-9 0 5-1 8-6 9Z" fill="#d9e2d4"/>
          <path d="m37 56 5-8" stroke="#d9e2d4" stroke-width="1.3"/>
        </template>

        <template v-else-if="kind === 'stationery'">
          <path d="m20 23 27-5 12 34-28 7c-5 1-8-2-9-6Z" fill="#92aab8"/>
          <path d="m26 51 30-7 2 9-28 7c-6 1-8-7-4-9Z" :fill="surface" stroke="#b8c5ca"/>
          <path d="m18 19 28-6 12 33-29 7c-5 1-7 3-6 7Z" :fill="object" stroke="#658395"/>
          <path d="m22 19 11 31" stroke="#aec2cc" stroke-width="1.2"/>
          <path d="m30 27 15-3m-13 8 10-2" stroke="#e2ebec" stroke-width="1.5"/>
          <path d="m43 49 4 11 2-4 4 1-4-10" fill="#be9c6f"/>
          <path d="m58 19 5-1 6 31-3 7-4-6Z" fill="#d8bd8b" stroke="#a9946f"/>
          <path d="m58 19 5-1 1 5-5 1Z" fill="#f1ede2"/>
          <path d="m62 50 4 6 3-7" fill="#f0e5cc"/>
          <path d="m65 54 1 2 1-3" stroke="#6b7980" stroke-width="1.5"/>
        </template>

        <template v-else-if="kind === 'sports'">
          <circle cx="40" cy="40" r="22" :fill="object" stroke="#819cae"/>
          <path d="M24 25c9 13 21 24 34 27M20 48c14-9 24-18 31-27M38 18c-6 12-4 32 4 44M18 36c12 7 31 5 43-2" stroke="#57788d" stroke-width="1.6"/>
          <path d="M27 25c4-4 9-5 13-5" stroke="#dbe7eb" stroke-width="1.8"/>
          <path d="m53 61 9-8 6 6-9 7Z" fill="#d5be93" stroke="#b2a181"/>
          <path d="m57 61 6-5" stroke="#f7ecd7" stroke-width="1.6"/>
        </template>

        <template v-else>
          <path d="M23 17h34v44l-4-3-4 3-5-3-4 3-4-3-5 3-4-3-4 3Z" :fill="surface" stroke="#b6c7d2"/>
          <path d="M23 17h34v10H23Z" :fill="object"/>
          <path d="M30 35h19m-19 6h14m-14 8h7m6 0h7" stroke="#90a8b8" stroke-width="1.8"/>
          <path d="M30 22h10" stroke="#e4edf1" stroke-width="1.2"/>
          <path d="M58 36v19l-2-1" stroke="#d5dfe5" stroke-width="1.4"/>
        </template>
      </g>
    </svg>
  </span>
</template>

<style scoped>
.category-artwork {
  --art-light: #9bb4c5;
  --art-dark: #6f8ea4;
  --art-tile: #edf2f7;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 52px;
  height: 52px;
  overflow: hidden;
  border: 1px solid rgb(97 122 145 / 11%);
  border-radius: 13px;
  background: var(--art-tile);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 80%), 0 2px 3px rgb(32 55 77 / 3%);
  vertical-align: middle;
  isolation: isolate;
}
.category-artwork svg { display: block; width: 100%; height: 100%; overflow: visible; }
.category-artwork--sm { width: 40px; height: 40px; border-radius: 10px; }
.category-artwork--lg { width: 72px; height: 72px; border-radius: 17px; }
.category-artwork--coffee { --art-tile: #f1eee8; }
.category-artwork--tea { --art-tile: #eef2e9; --art-light: #9db69b; --art-dark: #78977c; }
.category-artwork--meal { --art-tile: #eaf1f1; --art-light: #8baeb4; --art-dark: #587e89; }
.category-artwork--bakery { --art-tile: #f5f0e7; --art-light: #e4c18a; --art-dark: #bd8f55; }
.category-artwork--print { --art-tile: #ecf1f8; --art-light: #a8bfd4; --art-dark: #7394b1; }
.category-artwork--grocery { --art-tile: #edf1ec; --art-light: #aabec0; --art-dark: #809da3; }
.category-artwork--stationery { --art-tile: #eef0f5; --art-light: #8da9ba; --art-dark: #617f94; }
.category-artwork--sports { --art-tile: #ecf2f5; --art-light: #b8ccd6; --art-dark: #89a8ba; }
.art-object { transform-origin: 40px 48px; transition: transform 180ms ease; }
@media (hover: hover) and (prefers-reduced-motion: no-preference) {
  .category-artwork:hover .art-object { transform: translateY(-1px); }
}
@media (prefers-reduced-motion: reduce) { .art-object { transition: none; } }
</style>
