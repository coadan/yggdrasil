<script lang="ts">
  import PanelCard from './PanelCard.svelte';
  import { loadPanel } from '../lib/panels';

  export let panelId: string;
  export const selected = loadPanel(panelId);
  const refresh = () => loadPanel(panelId);
</script>

<template>
  <PanelCard />
</template>
