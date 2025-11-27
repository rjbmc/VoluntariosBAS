package util.sevilla.bancodealimentos.es;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;
// USANDO EL IMPORT CORRECTO DESCUBIERTO POR EL USUARIO
import com.microsoft.graph.serviceclient.GraphServiceClient;

import java.util.Map;

/**
 * Clase de utilidad para centralizar todas las interacciones con la API de Microsoft Graph para SharePoint.
 */
public final class SharepointUtil {

    // --- 1. CONFIGURACIÓN DE AUTENTICACIÓN ---
    private static final String MS_TENANT_ID = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec";
    private static final String MS_CLIENT_ID = "e15a2c05-a43a-487f-a78b-aa7fade86e7a";
    private static final String MS_CLIENT_SECRET = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd";

    // --- 2. IDS DE SITIOS Y LISTAS ---
    public static final String SITE_ID = "bancodealimentosdesevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdesevilla.sharepoint.com,85233b3b-482a-453b-8f3b-619f71c43ab3,7701358f-1a92-4f05-b1a1-8e07ad76e542";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdesevilla.sharepoint.com,85233b3b-482a-453b-8f3b-619f71c43ab3,7701358f-1a92-4f05-b1a1-8e07ad76e542";
    
    // ***** CAMBIO AQUÍ: Hecho 'public' para que sea visible desde otros ficheros *****
    public static final String LIST_ID_VOLUNTARIOS = "61fc2903-5b45-42da-8ad6-276f0215f084";
    public static final String LIST_ID_CAMPANAS = "f4ad1ff5-2fbf-472e-b0a6-3d1564950e0a";
    public static final String LIST_ID_TIENDAS = "89d67278-a9f3-49b0-a306-8c7f232d9c90";
    public static final String LIST_ID_VOLUNTARIOS_EN_CAMPANA = "0bdc7d33-4168-410f-b129-61f2e86b4b4f";

    private static GraphServiceClient graphClient = null;

    private SharepointUtil() {
        // Clase de utilidad no instanciable
    }

    /**
     * Obtiene una instancia del cliente de Graph. Si no existe, la crea y la autentica.
     * Es público para que los Servlets puedan usarlo.
     * @return Un cliente de Graph listo para usar.
     */
    public static synchronized GraphServiceClient getGraphClient() {
        if (graphClient == null) {
            if (MS_TENANT_ID.isEmpty() || MS_CLIENT_ID.isEmpty() || MS_CLIENT_SECRET.isEmpty()) {
                throw new IllegalStateException("Las credenciales de MS Graph no están configuradas en SharepointUtil.java");
            }
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(MS_CLIENT_ID)
                .clientSecret(MS_CLIENT_SECRET)
                .tenantId(MS_TENANT_ID)
                .build();
            
            final String[] scopes = new String[] { "https://graph.microsoft.com/.default" };
            graphClient = new GraphServiceClient(credential, scopes);
        }
        return graphClient;
    }
    
    // ***** CAMBIO AQUÍ: Hecho 'public' para poder usarlo desde cualquier servlet *****
    public static String getListId(String listName) {
        switch (listName.toLowerCase()) {
            case "voluntarios": return LIST_ID_VOLUNTARIOS;
            case "campanas": return LIST_ID_CAMPANAS;
            case "tiendas": return LIST_ID_TIENDAS;
            case "voluntarios_en_campana": return LIST_ID_VOLUNTARIOS_EN_CAMPANA;
            default: throw new IllegalArgumentException("Nombre de lista no reconocido: " + listName);
        }
    }

    public static void createListItem(String listName, Map<String, Object> data) {
        String listId = getListId(listName);
        FieldValueSet fieldValueSet = new FieldValueSet();
        fieldValueSet.setAdditionalData(data);
        ListItem listItem = new ListItem();
        listItem.setFields(fieldValueSet);
        getGraphClient().sites().bySiteId(SITE_ID).lists().byListId(listId).items().post(listItem);
    }

    public static void updateListItem(String listName, String itemId, Map<String, Object> data) {
        String listId = getListId(listName);
        FieldValueSet fieldValueSet = new FieldValueSet();
        fieldValueSet.setAdditionalData(data);
        getGraphClient().sites().bySiteId(SITE_ID).lists().byListId(listId).items().byListItemId(itemId).fields().patch(fieldValueSet);
    }

    public static void deleteListItem(String listName, String itemId) {
        String listId = getListId(listName);
        getGraphClient().sites().bySiteId(SITE_ID).lists().byListId(listId).items().byListItemId(itemId).delete();
    }

    public static String findListItemIdByFieldValue(String listName, String fieldName, String fieldValue) {
        String listId = getListId(listName);
        final String filterQuery = "fields/" + fieldName + " eq '" + fieldValue + "'";
        ListItemCollectionResponse result = getGraphClient().sites().bySiteId(SITE_ID).lists().byListId(listId).items()
            .get(config -> {
                config.queryParameters.filter = filterQuery;
                config.queryParameters.select = new String[]{"id"};
                config.queryParameters.top = 1;
            });
        if (result != null && result.getValue() != null && !result.getValue().isEmpty()) {
            return result.getValue().get(0).getId();
        }
        return null;
    }
}
