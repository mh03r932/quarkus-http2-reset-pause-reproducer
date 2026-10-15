package org.acme;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.RestForm;

import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;

@Path("/multipart")
public class MultipartResource {

    @POST
    @NonBlocking
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<String> post(@RestForm("name") String name) {
        return Uni.createFrom().item("multipart " + (name == null ? "null" : name.length() + " chars"));
    }
}
