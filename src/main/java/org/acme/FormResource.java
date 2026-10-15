package org.acme;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Uni;

@Path("/form")
public class FormResource {

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Uni<String> post(@FormParam("name") String name) {
        return Uni.createFrom().item("form " + name);
    }
}
