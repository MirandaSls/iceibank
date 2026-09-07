package br.pucminas.icei.iceibank.agencia;

import br.pucminas.icei.iceibank.agencia.security.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Anexa um JWT valido as requisicoes dos testes, ja que a API inteira e protegida. */
final class TokenDeTeste {

    private TokenDeTeste() {
    }

    static RequestPostProcessor deUsuario(JwtService jwtService) {
        return comToken(jwtService.gerarTokenDeUsuario("ana"));
    }

    static RequestPostProcessor deServico(JwtService jwtService, int idAgencia) {
        return comToken(jwtService.gerarTokenDeServico(idAgencia));
    }

    private static RequestPostProcessor comToken(String token) {
        return requisicao -> {
            requisicao.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return requisicao;
        };
    }
}
