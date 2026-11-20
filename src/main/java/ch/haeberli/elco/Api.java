package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** Abstraction over the HTTP client so the poller can be tested offline. */
interface Api {
    JsonNode getJson(String pathAndQuery) throws IOException, InterruptedException;
}
