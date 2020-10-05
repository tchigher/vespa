// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.restapi.resource;

import com.yahoo.application.Application;
import com.yahoo.application.Networking;
import com.yahoo.container.Container;
import com.yahoo.jdisc.http.server.jetty.JettyHttpServer;
import com.yahoo.slime.SlimeUtils;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

public class DocumentV1ApiApplicationTest {

    HttpClient client = HttpClient.newBuilder()
                                  .connectTimeout(Duration.ofSeconds(1))
                                  .build();
    int port;
    List<Throwable> errors;
    Phaser phaser;

    HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder(URI.create( "http://localhost:" + port + "/document/v1" + path))
                          .timeout(Duration.ofSeconds(5))
                          .header("Content-Type", "application/json");
    }

    final String servicesXml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
                               "<container version=\"1.0\">" +
                               "    <handler id=\"com.yahoo.document.restapi.resource.DocumentV1ApiTestHandler\">" +
                               "        <binding>http://*/document/v1/*</binding>" +
                               "    </handler>" +
                               "    <http>" +
                               "        <!-- This indicates that we want JDisc to allocate a port for us -->" +
                               "        <server id=\"default\" port=\"0\" />" +
                               "    </http>" +
                               "</container>";

    @Before
    public void setUp() {
        errors = new BlockingArrayQueue<>();
        phaser = new Phaser(1);
    }

    @Test
    @Ignore
    public void testWithHttpAndHang() throws InterruptedException {
        try (Application application = Application.fromServicesXml(servicesXml, Networking.enable)) {
            port = getFirstListenPort();
            System.err.println("Server listening on port " + port);

            Thread.sleep(Long.MAX_VALUE);
        }
    }

    @Test
    public void testWithHttp() throws InterruptedException, TimeoutException {
        Instant start;
        try (Application application = Application.fromServicesXml(servicesXml, Networking.enable)) {
            port = getFirstListenPort();
            // System.err.println("Server listening on port " + port);
            verifyPutDoc(0);

            start = Instant.now();
            for (int i = 0; i < 10000; i++) {
                verifyPutDoc(i);
                Thread.sleep(1);
            }


            phaser.awaitAdvanceInterruptibly(phaser.arrive(), 10, TimeUnit.SECONDS);


            verifyResponse("/ns/music/docid/?fieldSet=[all]", "GET", response -> assertEquals(200, response.statusCode()));
            verifyResponse("/ns/music/docid/?fieldSet=[all]", "GET", response -> assertEquals(200, response.statusCode()));
            verifyResponse("/ns/music/docid/?fieldSet=[all]", "GET", response -> assertEquals(200, response.statusCode()));
            verifyResponse("/ns/music/docid/?fieldSet=[all]", "GET", response -> assertEquals(200, response.statusCode()));
            verifyResponse("/ns/music/docid/?fieldSet=[all]", "GET", response -> assertEquals(10000, SlimeUtils.jsonToSlimeOrThrow(response.body())
                                                                                                               .get()
                                                                                                               .field("documents")
                                                                                                               .entries()));

            phaser.awaitAdvanceInterruptibly(phaser.arrive(), 10, TimeUnit.SECONDS);
            // Thread.sleep(Long.MAX_VALUE);
        }

        if (start != null)
            System.err.println("Requests took " + Duration.between(start, Instant.now()));

        errors.forEach(Throwable::printStackTrace);
        assertEquals(0, errors.size());
    }

    void verifyPutDoc(int i) {
        verifyResponse("/ns/music/docid/" + i,
                       "POST",
                       "{ \"fields\": { \"artist\": \"singer " + i + "\" } }",
                       response -> assertEquals(200, response.statusCode()));
    }

    void verifyGetDoc(int i) {
        verifyResponse("/ns/music/docid/" + i,
                       "GET",
                       response -> {
                           assertEquals(200, response.statusCode());
                           assertEquals("{" +
                                          "\"id\":\"id:ns:music::" + i + "\"," +
                                          "\"pathId\":\"/document/v1/ns/music/docid/" + i + "\"," +
                                          "\"fields\":{" +
                                            "\"artist\":\"singer " + i + "\"" +
                                          "}" +
                                        "}",
                                        response.body());
                       });
    }

    void verifyResponse(String path, String method, Consumer<HttpResponse<String>> assertions) {
        verifyResponse(path, method, "", assertions);
    }

    void verifyResponse(String path, String method, String body, Consumer<HttpResponse<String>> assertions) {
        phaser.register();
        client.sendAsync(newRequest(path).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                         HttpResponse.BodyHandlers.ofString(UTF_8))
              .handleAsync((response, error) -> {
                  if (error != null)
                      errors.add(error);
                  else try {
                      assertions.accept(response);
                  }
                  catch (Throwable t) {
                      errors.add(t);
                  }
                  phaser.arriveAndDeregister();
                  return null;
              });
    }

    private int getFirstListenPort() {
        return ((JettyHttpServer) Container.get().getServerProviderRegistry().allComponents().get(0)).getListenPort();
    }

}
