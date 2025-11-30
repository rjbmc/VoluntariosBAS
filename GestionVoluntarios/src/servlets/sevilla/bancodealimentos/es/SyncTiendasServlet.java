package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/sync-tiendas")
public class SyncTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";
    private static final int PAUSA_ENTRE_PETICIONES_MS = 400; 

    private static class TiendaData {
        int codigo; String denominacion; String sqlRowUUID;
        String direccion; BigDecimal lat; BigDecimal lon; String cp; String poblacion; String cadena; 
        int prioridad; boolean disponible; int huecos1, huecos2, huecos3, huecos4;

        TiendaData(ResultSet rs) throws SQLException {
            this.codigo = rs.getInt("codigo");
            this.denominacion = rs.getString("denominacion");
            this.sqlRowUUID = rs.getString("SqlRowUUID");
            this.direccion = rs.getString("Direccion");
            this.lat = rs.getBigDecimal("Lat");
            this.lon = rs.getBigDecimal("Lon");
            this.cp = rs.getString("cp");
            this.poblacion = rs.getString("Poblacion");
            this.cadena = rs.getString("Cadena");
            this.prioridad = rs.getInt("prioridad");
            this.disponible = "S".equalsIgnoreCase(rs.getString("disponible"));
            this.huecos1 = rs.getInt("HuecosTurno1");
            this.huecos2 = rs.getInt("HuecosTurno2");
            this.huecos3 = rs.getInt("HuecosTurno3");
            this.huecos4 = rs.getInt("HuecosTurno4");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        List<TiendaData> tiendasEnMemoria = new ArrayList<>();
        Set<String> uuidsEsperados = new HashSet<>();

        try (Connection conn = DatabaseUtil.getConnection()) {
            String sql = "SELECT * FROM tiendas WHERE SqlRowUUID IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TiendaData tienda = new TiendaData(rs);
                    tiendasEnMemoria.add(tienda);
                    uuidsEsperados.add(tienda.sqlRowUUID);
                }
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error crítico al leer desde la base de datos: " + e.getMessage());
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        try {
            String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada.");

            SharepointUtil.deleteAllListItems(SharepointUtil.SITE_ID, listId);

            for (int i = 0; i < tiendasEnMemoria.size(); i++) {
                TiendaData tienda = tiendasEnMemoria.get(i);
                FieldValueSet fields = new FieldValueSet();
                fields.getAdditionalData().put("Title", tienda.denominacion);
                fields.getAdditionalData().put("codigo", String.valueOf(tienda.codigo));
                fields.getAdditionalData().put("denominacion", tienda.denominacion);
                fields.getAdditionalData().put("direccion", tienda.direccion);
                fields.getAdditionalData().put("lat", tienda.lat);
                fields.getAdditionalData().put("lon", tienda.lon);
                fields.getAdditionalData().put("cp", tienda.cp);
                fields.getAdditionalData().put("poblacion", tienda.poblacion);
                fields.getAdditionalData().put("cadena", tienda.cadena);
                fields.getAdditionalData().put("prioridad", tienda.prioridad);
                fields.getAdditionalData().put("disponible", tienda.disponible);
                fields.getAdditionalData().put("huecosTurno1", tienda.huecos1);
                fields.getAdditionalData().put("huecosTurno2", tienda.huecos2);
                fields.getAdditionalData().put("huecosTurno3", tienda.huecos3);
                fields.getAdditionalData().put("huecosTurno4", tienda.huecos4);
                fields.getAdditionalData().put("SqlRowUUID", tienda.sqlRowUUID);
                SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fields);
                
                Thread.sleep(PAUSA_ENTRE_PETICIONES_MS);
                System.out.println("Creando tienda " + (i + 1) + "/" + tiendasEnMemoria.size());
            }

            System.out.println("VERIFICACIÓN FINAL: Pausando 10 segundos antes de comprobar...");
            Thread.sleep(10000);

            Set<String> uuidsReales = new HashSet<>();
            ListItemCollectionResponse itemsPostSync = SharepointUtil.getListItems(SharepointUtil.SITE_ID, listId);
            if (itemsPostSync != null && itemsPostSync.getValue() != null) {
                for (ListItem item : itemsPostSync.getValue()) {
                    Map<String, Object> fields = item.getFields().getAdditionalData();
                    if (fields.containsKey("SqlRowUUID")) {
                        Object uuidObj = fields.get("SqlRowUUID");
                        if (uuidObj instanceof String) {
                            uuidsReales.add((String) uuidObj);
                        }
                    }
                }
            }

            if (uuidsReales.size() < uuidsEsperados.size()) {
                 jsonResponse.addProperty("success", false);
                 jsonResponse.addProperty("message", "FALLO DE VERIFICACIÓN POST-RECONSTRUCCIÓN: Se esperaba crear " + uuidsEsperados.size() + " tiendas, pero solo se encontraron " + uuidsReales.size() + ". El problema de 'throttling' persiste.");
            } else {
                String successMessage = "¡Éxito verificado! Se han reconstruido y verificado " + uuidsReales.size() + " tiendas correctamente.";
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", successMessage);
            }
            response.getWriter().write(jsonResponse.toString());

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error durante el ciclo de reconstrucción: " + e.getMessage());
            response.getWriter().write(jsonResponse.toString());
        }
    }
}
