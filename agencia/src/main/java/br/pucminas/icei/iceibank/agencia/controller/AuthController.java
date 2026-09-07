package br.pucminas.icei.iceibank.agencia.controller;

import br.pucminas.icei.iceibank.agencia.dto.Erro;
import br.pucminas.icei.iceibank.agencia.dto.LoginRequest;
import br.pucminas.icei.iceibank.agencia.model.EstadoAgencia;
import br.pucminas.icei.iceibank.agencia.security.JwtService;
import br.pucminas.icei.iceibank.agencia.security.UsuariosEmMemoria;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller de autenticacao: unica rota publica da API. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UsuariosEmMemoria usuarios;
    private final JwtService jwtService;
    private final EstadoAgencia estado;

    public AuthController(UsuariosEmMemoria usuarios, JwtService jwtService, EstadoAgencia estado) {
        this.usuarios = usuarios;
        this.jwtService = jwtService;
        this.estado = estado;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest requisicao) {
        if (!usuarios.credenciaisValidas(requisicao.usuario(), requisicao.senha())) {
            estado.registro().registrar("LOGIN_RECUSADO", estado.relogio().eventoLocal(),
                    ContasController.detalhes("usuario", requisicao.usuario()));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new Erro("Usuario ou senha invalidos."));
        }

        String usuario = requisicao.usuario().trim().toLowerCase();
        String token = jwtService.gerarTokenDeUsuario(usuario);

        estado.registro().registrar("LOGIN", estado.relogio().eventoLocal(),
                ContasController.detalhes("usuario", usuario));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "tipo", "Bearer",
                "usuario", usuario,
                "agencia", estado.idAgencia(),
                "expiraEmSegundos", jwtService.validadeDoUsuarioEmSegundos()));
    }
}
