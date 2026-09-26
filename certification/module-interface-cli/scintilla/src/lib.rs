pub mod error {
    #[derive(Debug)]
    pub struct CliError(pub String);

    impl CliError {
        pub fn runtime(message: String) -> Self {
            Self(message)
        }
        pub fn usage(message: String) -> Self {
            Self(message)
        }
    }

    impl core::fmt::Display for CliError {
        fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
            formatter.write_str(&self.0)
        }
    }

    impl std::error::Error for CliError {}
}

pub mod flags {
    #[derive(Debug, Default)]
    pub struct CliArgs;
}

#[path = "commands/module_interfaces.rs"]
pub mod module_interfaces;
