package br.pucminas.icei.iceibank.agencia.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige um JWT valido em todas as rotas que leem ou modificam contas.
 *
 * <p>Rotas publicas: {@code /auth/login} (onde o token e obtido) e o preflight CORS.
 * A rota interna {@code /contas/{id}/creditar-remoto} so aceita token de servico, emitido por
 * uma agencia para outra - um token de pessoa nao serve ali, e vice-versa.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    public static final String ATRIBUTO_USUARIO = "usuarioAutenticado";

    private final JwtService jwtService;

    public JwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {

        if (rotaPublica(requisicao)) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        String cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho == null || !cabecalho.startsWith("Bearer ")) {
            recusar(resposta, "Token ausente. Envie o cabecalho Authorization: Bearer <token>.");
            return;
        }

        Claims claims;
        try {
            claims = jwtService.validar(cabecalho.substring("Bearer ".length()).trim());
        } catch (ExpiredJwtException expirado) {
            recusar(resposta, "Token expirado. Faca login novamente.");
            return;
        } catch (JwtException | IllegalArgumentException invalido) {
            recusar(resposta, "Token invalido.");
            return;
        }

        String tipoDoToken = String.valueOf(claims.get(JwtService.CLAIM_TIPO));
        String tipoExigido = rotaInternaEntreAgencias(requisicao)
                ? JwtService.TIPO_SERVICO
                : JwtService.TIPO_USUARIO;

        if (!tipoExigido.equals(tipoDoToken)) {
            recusar(resposta, "Token do tipo " + tipoDoToken + " nao autorizado nesta rota.");
            return;
        }

        requisicao.setAttribute(ATRIBUTO_USUARIO, claims.getSubject());
        cadeia.doFilter(requisicao, resposta);
    }

    private boolean rotaPublica(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return HttpMethod.OPTIONS.matches(requisicao.getMethod())
                || caminho.startsWith("/auth/")
                || caminho.equals("/error");
    }

    private boolean rotaInternaEntreAgencias(HttpServletRequest requisicao) {
        return requisicao.getRequestURI().endsWith("/creditar-remoto");
    }

    private void recusar(HttpServletResponse resposta, String mensagem) throws IOException {
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding("UTF-8");
        resposta.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
    }
}
