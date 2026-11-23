package cn.yunshu.erp;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class CarrierHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String read(URI uri) throws IOException, InterruptedException {
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"中国移动 HTTP "+response.statusCode());
        return response.body();
    }
}
