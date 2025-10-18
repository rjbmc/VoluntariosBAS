package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/crud-sharepoint")
public class SharepointCrudServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        try {
            // Parámetros comunes
            String siteId = request.getParameter("siteId");
            String listId = request.getParameter("listId");
            String action = request.getParameter("action");
            String itemId = request.getParameter("itemId");
            String payload = request.getParameter("payload"); // Datos del item en formato JSON

            if (siteId == null || listId == null || action == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print(gson.toJson(Map.of("error", "Parámetros 'siteId', 'listId' y 'action' son requeridos.")));
                return;
            }

            GraphServiceClient graphClient = SharepointUtil.getGraphClient();

            switch (action.toUpperCase()) {
                case "DELETE":
                    handleDelete(graphClient, siteId, listId, itemId, response, out);
                    break;
                case "CREATE":
                    handleCreate(graphClient, siteId, listId, payload, response, out);
                    break;
                case "UPDATE":
                    handleUpdate(graphClient, siteId, listId, itemId, payload, response, out);
                    break;
                default:
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    out.print(gson.toJson(Map.of("error", "Acción no válida. Use CREATE, UPDATE o DELETE.")));
                    break;
            }

        } catch (Exception e) {
            System.err.println("Error en SharepointCrudServlet: " + e.getMessage());
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", "Error interno en el servidor: " + e.getMessage())));
        } finally {
            out.flush();
        }
    }

    private void handleDelete(GraphServiceClient client, String siteId, String listId, String itemId, HttpServletResponse res, PrintWriter out) throws IOException {
        if (itemId == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'itemId' es requerido para la acción DELETE.")));
            return;
        }
        
        // Lógica de borrado en Microsoft Graph SDK v6
        client.sites().bySiteId(siteId).lists().byListId(listId).items().byListItemId(itemId).delete();
        
        // La API de Graph devuelve 204 No Content en caso de éxito, sin cuerpo.
        res.setStatus(HttpServletResponse.SC_OK);
        out.print(gson.toJson(Map.of("success", "Elemento borrado correctamente.")));
    }

    private void handleCreate(GraphServiceClient client, String siteId, String listId, String payload, HttpServletResponse res, PrintWriter out) throws IOException {
        if (payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "El 'payload' (JSON) es requerido para la acción CREATE.")));
             return;
        }
        
        try {
            // Convertir el payload JSON a un Mapa que el SDK pueda entender
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());

            FieldValueSet fieldValueSet = new FieldValueSet();
            fieldValueSet.setAdditionalData(fields);

            ListItem requestBody = new ListItem();
            requestBody.setFields(fieldValueSet);
            
            ListItem createdItem = client.sites().bySiteId(siteId).lists().byListId(listId).items().post(requestBody);

            res.setStatus(HttpServletResponse.SC_CREATED);
            out.print(gson.toJson(createdItem)); // Devolver el item creado

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage())));
        }
    }

    private void handleUpdate(GraphServiceClient client, String siteId, String listId, String itemId, String payload, HttpServletResponse res, PrintWriter out) throws IOException {
        if (itemId == null || payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "Los parámetros 'itemId' y 'payload' son requeridos para UPDATE.")));
             return;
        }
        
         try {
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());

            FieldValueSet requestBody = new FieldValueSet();
            requestBody.setAdditionalData(fields);
            
            FieldValueSet updatedFields = client.sites().bySiteId(siteId).lists().byListId(listId).items().byListItemId(itemId).fields().patch(requestBody);

            res.setStatus(HttpServletResponse.SC_OK);
            out.print(gson.toJson(updatedFields)); // Devolver los campos actualizados

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage())));
        }
    }
}
