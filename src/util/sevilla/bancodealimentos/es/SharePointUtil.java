package util.sevilla.bancodealimentos.es;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;

public class SharePointUtil {

    public static final String SITE_ID = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdsevilla.sharepoint.com,98b8faf3-b1b7-4a7b-8cd8-744fbe8c8c79,b2a4e075-4f56-47fe-9fc5-09466a41a27a";
    public static final String LIST_NAME_TIENDAS = "Tiendas";
    public static final String FIELD_CODIGO_TIENDA = "codigo";

    private static GraphServiceClient graphClient = null;

    private static void initializeGraphClient() {
        if (graphClient == null) {
            final String clientId = "e15a2c05-a43a-487f-a78b-aa7fade86e7a";
            final String tenantId = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec";
            final String clientSecret = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd";
            
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();

            graphClient = new GraphServiceClient(credential);
        }
    }

    public static String getListId(String targetSiteId, String listName) throws Exception {
        initializeGraphClient();
        ListCollectionResponse lists = graphClient.sites().bySiteId(targetSiteId).lists().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = "displayName eq '" + listName + "'";
        });
        if (lists != null && lists.getValue() != null && !lists.getValue().isEmpty()) {
            return lists.getValue().get(0).getId();
        }
        return null;
    }

    public static ListCollectionResponse getAllLists(String targetSiteId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().get();
    }

    public static ColumnDefinitionCollectionResponse getListColumns(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).columns().get();
    }

    public static ListItemCollectionResponse getListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        
        ListItemCollectionResponse page = graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.expand = new String[]{"fields"};
        });

        if (page == null) {
            return new ListItemCollectionResponse();
        }

        final List<ListItem> allItems = new LinkedList<>();
        allItems.addAll(page.getValue());

        while (page.getOdataNextLink() != null) {
            page = graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().withUrl(page.getOdataNextLink()).get();
            if (page != null) {
                allItems.addAll(page.getValue());
            }
        }
        
        final ListItemCollectionResponse allItemsResponse = new ListItemCollectionResponse();
        allItemsResponse.setValue(allItems);
        
        return allItemsResponse;
    }

    public static ListItem createListItem(String targetSiteId, String listId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        ListItem newItem = new ListItem();
        newItem.setFields(fields);
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().post(newItem);
    }

    public static FieldValueSet updateListItem(String targetSiteId, String listId, String itemId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).fields().patch(fields);
    }

    public static void deleteListItem(String targetSiteId, String listId, String itemId) throws Exception {
        initializeGraphClient();
        graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).delete();
    }

    public static void deleteAllListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        ListItemCollectionResponse items = getListItems(targetSiteId, listId);
        if (items != null && items.getValue() != null) {
            for (ListItem item : items.getValue()) {
                deleteListItem(targetSiteId, listId, item.getId());
            }
        }
    }

    public static String findItemIdByFieldValue(Connection conn, String siteId, String listId, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        String escapedValue = fieldValue.replace("'", "''");
        String filter = "fields/" + fieldName + " eq '" + escapedValue + "'";

        try {
            ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = filter;
                requestConfiguration.queryParameters.select = new String[]{"id"};
                requestConfiguration.queryParameters.top = 1;
                requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });

            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                return response.getValue().get(0).getId();
            }
        } catch (Exception e) {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "Error en findItemIdByFieldValue: " + e.getMessage());
            return null;
        }
        return null;
    }
    
    public static Map<String, Object> findItemByFieldValue(Connection conn, String siteId, String listName, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        String listId = getListId(siteId, listName);
        if (listId == null) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "findItemByFieldValue: No se pudo encontrar el listId para la lista: " + listName);
            throw new IOException("La lista de SharePoint '" + listName + "' no fue encontrada.");
        }

        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        String escapedValue = fieldValue.replace("'", "''");
        String filter = "fields/" + fieldName + " eq '" + escapedValue + "'";

        try {
            ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = filter;
                requestConfiguration.queryParameters.expand = new String[]{"fields"};
                requestConfiguration.queryParameters.top = 1;
                requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });

            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                LogUtil.logOperation(conn, "SP_QUERY_SUCCESS", "SYSTEM", "Item encontrado en lista '" + listName + "' con filtro: " + filter);
                return response.getValue().get(0).getFields().getAdditionalData();
            } else {
                 LogUtil.logOperation(conn, "SP_QUERY_NOT_FOUND", "SYSTEM", "Item no encontrado en lista '" + listName + "' con filtro: " + filter);
                return null;
            }
        } catch (Exception e) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "Error en findItemByFieldValue con filtro '" + filter + "': " + e.getMessage());
            throw e;
        }
    }

    public static Map<String, Object> getTiendaFromSP(Connection conn, String codigoTienda) throws Exception {
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Iniciando búsqueda para código: " + codigoTienda);
        initializeGraphClient();

        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);
        if (listId == null) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "getTiendaFromSP: No se pudo encontrar el listId para la lista: " + LIST_NAME_TIENDAS);
            throw new IOException("La lista de SharePoint '" + LIST_NAME_TIENDAS + "' no fue encontrada.");
        }
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: listId obtenido: " + listId);

        String filter = "fields/" + FIELD_CODIGO_TIENDA + " eq " + codigoTienda;
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Buscando tienda con filtro: " + filter);

        ListItemCollectionResponse response = graphClient.sites().bySiteId(SITE_ID).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = filter;
            requestConfiguration.queryParameters.expand = new String[]{"fields"};
            requestConfiguration.queryParameters.top = 1;
            requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
        });

        if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Se encontró la tienda para código " + codigoTienda);
            return response.getValue().get(0).getFields().getAdditionalData();
        } else {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: No se encontró ninguna tienda para código " + codigoTienda + " con el filtro " + filter + ".");
            return null;
        }
    }

    public static void syncTienda(Connection conn, Map<String, Object> storeData) throws Exception {
        initializeGraphClient();
        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);
        if (listId == null) {
            throw new IOException("La lista de SharePoint '" + LIST_NAME_TIENDAS + "' no fue encontrada.");
        }

        String uuid = (String) storeData.get("SqlRowUUID");
        String itemId = findItemIdByFieldValue(conn, SITE_ID, listId, "SqlRowUUID", uuid);

        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(storeData);

        if (itemId != null) {
            // Update
            updateListItem(SITE_ID, listId, itemId, fields);
        } else {
            // Create
            createListItem(SITE_ID, listId, fields);
        }
    }

    public static void logError(String listName, String uuid, String message) {
        try (FileWriter writer = new FileWriter("../logs/sharepoint_sync_errors.log", true)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write(String.format("[%s] Error en lista '%s' para UUID '%s': %s\n", timestamp, listName, uuid, message));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}