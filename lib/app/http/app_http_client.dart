import 'package:dio/dio.dart';
import 'package:retrofit/retrofit.dart';

part 'app_http_client.g.dart';

@RestApi(baseUrl: "https://example.com/api")
abstract class AppHttpClient {
  factory AppHttpClient(Dio dio, {String baseUrl}) = _AppHttpClient;
}
