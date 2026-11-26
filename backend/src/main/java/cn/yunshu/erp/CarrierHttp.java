package cn.yunshu.erp;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class CarrierHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Response(int status,String body) {}

    String read(URI uri) throws IOException, InterruptedException {
        Response response=get(uri);
        if(response.status()!=200)throw new IOException("HTTP "+response.status());
        return response.body();
    }

    Response get(URI uri) throws IOException, InterruptedException {
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("Accept","application/json").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(),response.body());
    }
}
