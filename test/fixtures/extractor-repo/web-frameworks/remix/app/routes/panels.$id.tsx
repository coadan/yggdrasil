import { redirect } from '@remix-run/node';
import PanelDetails from '../components/PanelDetails';

export async function loader() {
  return { panel: null };
}

export const action = async () => {
  return redirect('/panels');
};

export default function PanelRoute() {
  return <PanelDetails />;
}
