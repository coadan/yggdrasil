<?php

use Illuminate\Support\Facades\Route;

Route::get('/panels', [PanelController::class, 'index']);
$routes->post('/panels/{id}', 'PanelController::update');
#[Route('/admin/panels', name: 'admin_panels')]
final class PanelRoutes {}
