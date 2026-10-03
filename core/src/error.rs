use std::fmt::Display;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("mls: {0}")]
    Mls(String),
    #[error("crypto: {0}")]
    Crypto(&'static str),
    #[error("invalid input: {0}")]
    Invalid(&'static str),
}

pub type Result<T> = std::result::Result<T, Error>;

pub(crate) fn mls<E: Display>(e: E) -> Error {
    Error::Mls(e.to_string())
}
