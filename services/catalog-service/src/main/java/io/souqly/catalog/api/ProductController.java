package io.souqly.catalog.api;

import java.net.URI;

import io.souqly.catalog.api.ApiModels.CreateProductRequest;
import io.souqly.catalog.api.ApiModels.ImageUploadRequest;
import io.souqly.catalog.api.ApiModels.ImageUploadResponse;
import io.souqly.catalog.api.ApiModels.ListingRequest;
import io.souqly.catalog.api.ApiModels.ProductResponse;
import io.souqly.catalog.product.Product;
import io.souqly.catalog.product.ProductImageService;
import io.souqly.catalog.product.ProductService;
import io.souqly.platform.security.Caller;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listings use optimistic concurrency over HTTP: every response carries the listing's version as
 * an {@code ETag}, and an update must send it back in {@code If-Match}. Two editors can therefore
 * never silently overwrite each other's changes.
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    private final ProductService products;
    private final ProductImageService images;

    ProductController(ProductService products, ProductImageService images) {
        this.products = products;
        this.images = images;
    }

    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request,
            Authentication authentication) {
        Product product = products.create(request.sku(), request.toDetails(), Caller.from(authentication));
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id())).eTag(etag(product))
                .body(ProductResponse.from(product));
    }

    @GetMapping("/{id}")
    ResponseEntity<ProductResponse> get(@PathVariable String id, Authentication authentication) {
        return withEtag(products.get(id, Caller.from(authentication)));
    }

    @PutMapping("/{id}")
    ResponseEntity<ProductResponse> update(@PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ListingRequest request, Authentication authentication) {
        long expectedVersion = Preconditions.parseIfMatch(ifMatch);
        return withEtag(products.update(id, expectedVersion, request.toDetails(), Caller.from(authentication)));
    }

    @PostMapping("/{id}/publish")
    ResponseEntity<ProductResponse> publish(@PathVariable String id, Authentication authentication) {
        return withEtag(products.publish(id, Caller.from(authentication)));
    }

    @PostMapping("/{id}/archive")
    ResponseEntity<ProductResponse> archive(@PathVariable String id, Authentication authentication) {
        return withEtag(products.archive(id, Caller.from(authentication)));
    }

    /** Step 1 of an upload: returns a signed URL the browser PUTs the file to directly. */
    @PostMapping("/{id}/images")
    ResponseEntity<ImageUploadResponse> requestImageUpload(@PathVariable String id,
            @Valid @RequestBody ImageUploadRequest request, Authentication authentication) {
        var ticket = images.requestUpload(id, request.contentType(), request.sizeBytes(), Caller.from(authentication));
        var upload = ticket.upload();
        return ResponseEntity.status(201).body(new ImageUploadResponse(ticket.image().imageId(), upload.url(), "PUT",
                upload.headers(), upload.expiresAt()));
    }

    /** Step 2: the file is verified and published, or rejected and deleted. */
    @PostMapping("/{id}/images/{imageId}/complete")
    ResponseEntity<ProductResponse> completeImageUpload(@PathVariable String id, @PathVariable String imageId,
            Authentication authentication) {
        return withEtag(images.completeUpload(id, imageId, Caller.from(authentication)));
    }

    private static ResponseEntity<ProductResponse> withEtag(Product product) {
        return ResponseEntity.ok().eTag(etag(product)).body(ProductResponse.from(product));
    }

    static String etag(Product product) {
        return "\"" + product.version() + "\"";
    }
}
