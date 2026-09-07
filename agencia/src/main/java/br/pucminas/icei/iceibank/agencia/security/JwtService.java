package br.pucminas.icei.iceibank.agencia.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Geracao e validacao dos tokens JWT.
 *
 * <p>Existem dois tipos de token, distinguidos pela claim {@code tipo}:
 *
 * <ul>
 *   <li>{@code USUARIO} - emitido no login, usado pelo frontend nas rotas de conta;</li>
 *   <li>{@code SERVICO} - emitido por uma agencia para falar com outra agencia
 *       ({@code creditar-remoto}). Nao pertence a nenhuma pessoa.</li>
 * </ul>
 *
 * <p>As tres agencias compartilham a mesma chave secreta, logo qualquer uma delas consegue
 * validar a assinatura sem consultar as outras - e justamente essa a vantagem do JWT.
 */
@Service
public class JwtService {

    public static final String TIPO_USUARIO = "USUARIO";
    public static final String TIPO_SERVICO = "SERVICO";
    public static final String CLAIM_TIPO = "tipo";

    private final SecretKey chave;
    private final Duration validadeDoUsuario;
    private final Duration validadeDoServico;

    public JwtService(
            @Value("${iceibank.jwt.segredo}") String segredo,
            @Value("${iceibank.jwt.validade-minutos:15}") long validadeMinutos,
            @Value("${iceibank.jwt.validade-servico-segundos:30}") long validadeServicoSegundos) {
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        this.validadeDoUsuario = Duration.ofMinutes(validadeMinutos);
        this.validadeDoServico = Duration.ofSeconds(validadeServicoSegundos);
    }

    public String gerarTokenDeUsuario(String usuario) {
        return gerarToken(usuario, TIPO_USUARIO, validadeDoUsuario);
    }

    public String gerarTokenDeServico(int idAgencia) {
        return gerarToken("agencia-" + idAgencia, TIPO_SERVICO, validadeDoServico);
    }

    public String gerarToken(String assunto, String tipo, Duration validade) {
        Instant agora = Instant.now();
        return Jwts.builder()
                .subject(assunto)
                .claim(CLAIM_TIPO, tipo)
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(validade)))
                .signWith(chave)
                .compact();
    }

    /**
     * Valida assinatura e expiracao do token.
     *
     * @throws JwtException se o token for invalido, adulterado ou estiver expirado
     */
    public Claims validar(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(chave)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long validadeDoUsuarioEmSegundos() {
        return validadeDoUsuario.toSeconds();
    }
}
