mod gateway;

use crate::gateway::serve;

pub struct Config {
    pub port: u16,
}

pub async fn run(config: Config) {
    serve(config.port).await;
}
