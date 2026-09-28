use zed_extension_api as zed;

// Пути по умолчанию — машина разработки. Каждый из них можно переопределить
// переменной окружения, чтобы расширение работало с любым экземпляром сервера
// (например, скачанным из GitHub-релиза) без пересборки.
const JAVA: &str = "/usr/lib/jvm/java-26-openjdk/bin/java";
const CAPECRAFT_LSP_JAR: &str = "/mnt/sda_home/gg_tv/CapeCraft/artifacts/capecraft-lsp.jar";

fn env_or(name: &str, fallback: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| fallback.to_string())
}

struct KoreN;

impl zed::Extension for KoreN {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        _worktree: &zed::Worktree,
    ) -> zed::Result<zed::Command> {
        Ok(zed::Command {
            command: env_or("CAPECRAFT_JAVA", JAVA),
            args: vec!["-jar".into(), env_or("CAPECRAFT_LSP_JAR", CAPECRAFT_LSP_JAR).into()],
            env: Default::default(),
        })
    }
}

zed::register_extension!(KoreN);
