package util.sevilla.bancodealimentos.es;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SharepointUtil {

    private static GraphServiceClient<okhttp3.Request> graphClient = null;
    private static TokenCredentialAuthProvider authProvider = null;

    /**
     * Devuelve una instancia singleton del cliente de Microsoft Graph.
     * La primera vez que se llama, se autentica usando las variables de entorno.
     * @return Un cliente de GraphServiceClient listo para usar.
     * @throws IllegalStateException si alguna de las variables de entorno requeridas no está configurada.
     */
    public static GraphServiceClient<okhttp3.Request> getGraphClient() {
        if (graphClient == null) {
            // Leer las credenciales desde las variables de entorno del sistema
            String tenantId = System.getenv("MS_TENANT_ID");
            String clientId = System.getenv("MS_CLIENT_ID");
            String clientSecret = System.getenv("MS_CLIENT_SECRET");

            // Validar que todas las variables de entorno necesarias están presentes
            if (Objects.isNull(tenantId) || Objects.isNull(clientId) || Objects.isNull(clientSecret) ||
                tenantId.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
                System.err.println("Error crítico: Faltan una o más variables de entorno para la autenticación de SharePoint (MS_TENANT_ID, MS_CLIENT_ID, MS_CLIENT_SECRET).");
                throw new IllegalStateException("Las variables de entorno para la autenticación de SharePoint no están configuradas.");
            }

            // El proveedor de credenciales se encargará de obtener y refrescar el token de acceso
            final ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

            // Definir el "scope" o alcance de los permisos. ".default" usa los permisos
            // que se han asignado a la aplicación en Azure Active Directory.
            final List<String> scopes = Arrays.asList("https://graph.microsoft.com/.default");

            // Crear el proveedor de autenticación
            authProvider = new TokenCredentialAuthProvider(scopes, credential);

            // Construir el cliente de Graph
            graphClient = GraphServiceClient.builder()
                .authenticationProvider(authProvider)
                .buildClient();
        }
        return graphClient;
    }
}
