package org.acme;

import java.io.IOException;
import java.io.InputStream;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/blocking")
public class BlockingStreamResource {

    @POST
    public String post(InputStream body) throws IOException {
        return "read " + body.readAllBytes().length;
    }
}
