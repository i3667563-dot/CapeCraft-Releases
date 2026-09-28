use zed_extension_api as zed;

const JAVA: &str = "/usr/lib/jvm/java-26-openjdk/bin/java";
const CAPECRAFT_LSP_JAR: &str = "/mnt/sda_home/gg_tv/CapeCraft/artifacts/capecraft-lsp.jar";

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
            command: JAVA.into(),
            args: vec!["-jar".into(), CAPECRAFT_LSP_JAR.into()],
            env: Default::default(),
        })
    }
}

zed::register_extension!(KoreN);
