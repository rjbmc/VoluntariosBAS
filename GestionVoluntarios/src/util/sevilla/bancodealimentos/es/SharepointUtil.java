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
    private static final String SITE_ID = "bancodealimentosdesevilla.sharepoint.com,85233b3b-482a-453b-8f3b-619f71c43ab3,7701358f-1a92-4f05-b1a1-8e07ad76e542";
    private static final String LIST_ID_VOLUNTARIOS = "a7b8e5c3-00d9-4884-a131-a20e3a6c9e07";
    private static final String LIST_ID_CAMPANAS = "LIST_ID_DE_CAMPANAS_AQUI"; // Reemplazar
    private static final String LIST_ID_TIENDAS = "LIST_ID_DE_TIENDAS_AQUI"; // Reemplazar
    private static final String LIST_ID_VOLUNTARIOS_EN_CAMPANA = "LIST_ID_DE_VOLUNTARIOS_EN_CAMPANA_AQUI"; // Reemplazar

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
    
    private static String getListId(String listName) {
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
        getGraphClient().sites().bySiteId(SITE_ID).lists().byListId(listId).items().byListItemId(itemId).getFields().patch(fieldValueSet);
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