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
 *
 * <p>No Sprint 2 a rota interna {@code creditar-remoto} deixou de existir: o credito entre
 * agencias chega pelo RabbitMQ, que nao passa por este filtro (ver RESPOSTAS.md, Parte C).
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
        if (!JwtService.TIPO_USUARIO.equals(tipoDoToken)) {
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

    private void recusar(HttpServletResponse resposta, String mensagem) throws IOException {
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding("UTF-8");
        resposta.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
    }
}
