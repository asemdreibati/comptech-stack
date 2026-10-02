package io.souqly.search.api;

class InvalidSearchException extends RuntimeException {

    InvalidSearchException(String message) {
        super(message);
    }
}
