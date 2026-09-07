package br.pucminas.icei.iceibank.agencia.security;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Base de usuarios do ICEIBank, em memoria - do mesmo jeito que as contas.
 *
 * <p>As tres agencias carregam a mesma lista, entao qualquer pessoa consegue fazer login em
 * qualquer agencia (a particao vale para contas, nao para usuarios). Guardar senha em texto
 * puro so e aceitavel porque este sprint nao tem banco de dados nem cadastro real; num sistema
 * de verdade a senha seria guardada como hash (bcrypt/argon2).
 */
@Component
public class UsuariosEmMemoria {

    private final Map<String, String> senhasPorUsuario = Map.of(
            "ana", "senha123",
            "bruno", "senha123",
            "carla", "senha123");

    public boolean credenciaisValidas(String usuario, String senha) {
        if (usuario == null || senha == null) {
            return false;
        }
        String senhaCadastrada = senhasPorUsuario.get(usuario.trim().toLowerCase());
        return senhaCadastrada != null && senhaCadastrada.equals(senha);
    }
}
