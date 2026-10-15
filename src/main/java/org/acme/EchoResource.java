package org.acme;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/echo")
public class EchoResource {

    @POST
    public String echo(String body) {
        return "echo " + body;
    }
}
