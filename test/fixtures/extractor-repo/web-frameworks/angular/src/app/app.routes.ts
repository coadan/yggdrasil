import { Routes } from '@angular/router';
import { PanelListComponent } from './panel-list.component';
import { PanelDetailsComponent } from './panel-details.component';

export const routes: Routes = [
  { path: '', component: PanelListComponent },
  { path: 'panels/:id', component: PanelDetailsComponent },
  {
    path: 'reports',
    loadChildren: () => import('./reports/reports.routes').then((m) => m.routes)
  },
  { path: 'old-panels', redirectTo: 'panels' }
];
