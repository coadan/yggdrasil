package Acme::Panels::PanelService;

use JSON::MaybeXS;
require Acme::Panels::PanelClient;

sub load_panel {
  my ($id) = @_;
  return $id;
}

sub _normalize_id {
  my ($id) = @_;
  return $id;
}

1;
