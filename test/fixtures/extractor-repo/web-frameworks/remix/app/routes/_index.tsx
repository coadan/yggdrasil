import { json } from '@remix-run/node';
import PanelList from '../components/PanelList';

export async function loader() {
  return json({ panels: [] });
}

export default function IndexRoute() {
  return <PanelList />;
}
