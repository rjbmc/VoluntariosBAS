package util.sevilla.bancodealimentos.es;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import java.util.Objects;

public class SharepointUtil {

    private static GraphServiceClient graphClient = null;

    /**
     * Devuelve una instancia singleton del cliente de Microsoft Graph (v6).
     * La primera vez que se llama, se autentica usando las variables de entorno.
     * @return Un cliente de GraphServiceClient listo para usar.
     * @throws IllegalStateException si alguna de las variables de entorno requeridas no está configurada.
     */
    public static GraphServiceClient getGraphClient() {
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

            // El scope (alcance) de los permisos. ".default" usa los permisos
            // que se han asignado a la aplicación en Azure Active Directory.
            final String[] scopes = new String[]{"https://graph.microsoft.com/.default"};

            // Construir el cliente de Graph para v6 (la forma ha cambiado y es más simple)
            graphClient = new GraphServiceClient(credential, scopes);
        }
        return graphClient;
    }
}
