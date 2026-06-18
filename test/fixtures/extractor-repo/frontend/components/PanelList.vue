<template>
  <PanelCard :panel="selected" />
</template>

<script setup lang="ts">
import PanelCard from './PanelCard.vue';
import { loadPanel } from '../lib/panels';

export const selected = loadPanel();
const refresh = () => loadPanel();
</script>
