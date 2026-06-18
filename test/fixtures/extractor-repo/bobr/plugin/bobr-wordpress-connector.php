<?php

namespace Bobr\WordPress;

use Bobr\Support\Client;

require_once __DIR__ . '/includes/client.php';

class Connector {
    public const DEFAULT_HOOK = 'init';

    public function register(): void {
        add_action('init', [$this, 'boot']);
    }

    private function boot(): void {
    }
}

function bobr_connector(): Connector {
    return new Connector();
}
