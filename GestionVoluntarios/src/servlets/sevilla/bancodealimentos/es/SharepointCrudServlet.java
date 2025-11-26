package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
// USANDO EL IMPORT CORRECTO DESCUBIERTO POR EL USUARIO
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
            // Parámetros
            String listName = request.getParameter("listName");
            String action = request.getParameter("action");
            String itemId = request.getParameter("itemId");
            String payload = request.getParameter("payload"); // Datos del item en formato JSON

            if (listName == null || action == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print(gson.toJson(Map.of("error", "Parámetros 'listName' y 'action' son requeridos.")));
                return;
            }

            // Obtener el cliente de Graph desde la clase de utilidad
            GraphServiceClient graphClient = SharepointUtil.getGraphClient();

            switch (action.toUpperCase()) {
                case "DELETE":
                    handleDelete(listName, itemId, response, out);
                    break;
                case "CREATE":
                    handleCreate(listName, payload, response, out);
                    break;
                case "UPDATE":
                    handleUpdate(listName, itemId, payload, response, out);
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

    private void handleDelete(String listName, String itemId, HttpServletResponse res, PrintWriter out) throws IOException {
        if (itemId == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'itemId' es requerido para la acción DELETE.")));
            return;
        }
        
        SharepointUtil.deleteListItem(listName, itemId);
        
        res.setStatus(HttpServletResponse.SC_OK);
        out.print(gson.toJson(Map.of("success", "Elemento borrado correctamente.")));
    }

    private void handleCreate(String listName, String payload, HttpServletResponse res, PrintWriter out) throws IOException {
        if (payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "El 'payload' (JSON) es requerido para la acción CREATE.")));
             return;
        }
        
        try {
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());
            SharepointUtil.createListItem(listName, fields);

            res.setStatus(HttpServletResponse.SC_CREATED);
            out.print(gson.toJson(Map.of("success", "Elemento creado correctamente.")));

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage())));
        }
    }

    private void handleUpdate(String listName, String itemId, String payload, HttpServletResponse res, PrintWriter out) throws IOException {
        if (itemId == null || payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "Los parámetros 'itemId' y 'payload' son requeridos para UPDATE.")));
             return;
        }
        
         try {
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());
            SharepointUtil.updateListItem(listName, itemId, fields);

            res.setStatus(HttpServletResponse.SC_OK);
            out.print(gson.toJson(Map.of("success", "Elemento actualizado correctamente.")));

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage())));
        }
    }
}
