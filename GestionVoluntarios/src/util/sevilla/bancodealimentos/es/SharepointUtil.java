package util.sevilla.bancodealimentos.es;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import java.util.Objects;

public class SharepointUtil {
	// IDs de los sitios de SharePoint
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdsevilla.sharepoint.com,98b8faf3-b1b7-4a7b-8cd8-744fbe8c8c79,b2a4e075-4f56-47fe-9fc5-09466a41a27a";
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
//            String tenantId = System.getenv("MS_TENANT_ID"); // aaea4167-1f45-4bba-ac5a-0bfb33dd3dec 
//            String clientId = System.getenv("MS_CLIENT_ID"); // e15a2c05-a43a-487f-a78b-aa7fade86e7a 
//            String clientSecret = System.getenv("MS_CLIENT_SECRET"); // 2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd 
        	
        	String tenantId = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec"; // tu TENANT_ID real 
        	String clientId = "e15a2c05-a43a-487f-a78b-aa7fade86e7a"; // Application (client) ID 
        	String clientSecret = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd"; // valor del secreto 
            
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
