# Servidor estatico minimo para o frontend web (02-frontend-web) em http://localhost:5500.
# Existe para o PC do laboratorio nao precisar de Python nem Node: usa so o PowerShell (.NET).
# Alternativa equivalente, se tiver Python:  cd 02-frontend-web; python -m http.server 5500
param([int]$Porta = 5500)

$host.UI.RawUI.WindowTitle = "ICEIBank - Frontend (porta $Porta)"
$pasta = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) '02-frontend-web'))
$tipos = @{
    '.html' = 'text/html; charset=utf-8'
    '.js'   = 'text/javascript; charset=utf-8'
    '.css'  = 'text/css; charset=utf-8'
    '.json' = 'application/json; charset=utf-8'
    '.png'  = 'image/png'
    '.svg'  = 'image/svg+xml'
    '.ico'  = 'image/x-icon'
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Porta/")
$listener.Start()
Write-Host "Frontend servido em http://localhost:$Porta  (feche esta janela para parar)" -ForegroundColor Green

try {
    while ($listener.IsListening) {
        $contexto = $listener.GetContext()
        $resposta = $contexto.Response
        try {
            $caminho = [Uri]::UnescapeDataString($contexto.Request.Url.AbsolutePath).TrimStart('/')
            if ([string]::IsNullOrEmpty($caminho)) { $caminho = 'index.html' }
            $arquivo = [IO.Path]::GetFullPath((Join-Path $pasta $caminho))

            if ($arquivo.StartsWith($pasta, [StringComparison]::OrdinalIgnoreCase) -and
                (Test-Path -LiteralPath $arquivo -PathType Leaf)) {
                $bytes = [IO.File]::ReadAllBytes($arquivo)
                $extensao = [IO.Path]::GetExtension($arquivo).ToLower()
                if ($tipos.ContainsKey($extensao)) {
                    $resposta.ContentType = $tipos[$extensao]
                } else {
                    $resposta.ContentType = 'application/octet-stream'
                }
                $resposta.ContentLength64 = $bytes.Length
                $resposta.OutputStream.Write($bytes, 0, $bytes.Length)
                Write-Host "200 /$caminho"
            } else {
                $resposta.StatusCode = 404
                Write-Host "404 /$caminho" -ForegroundColor Yellow
            }
        } finally {
            $resposta.Close()
        }
    }
} finally {
    $listener.Stop()
}
