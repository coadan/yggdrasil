import { proxyActivities } from '@temporalio/workflow';

export async function panelWorkflow(id: string): Promise<void> {
  const activities = proxyActivities<{ loadPanel(id: string): Promise<void> }>({
    startToCloseTimeout: '1 minute',
  });
  await activities.loadPanel(id);
}
