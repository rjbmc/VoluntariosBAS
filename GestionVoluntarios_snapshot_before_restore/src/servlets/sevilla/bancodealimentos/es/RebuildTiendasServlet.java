package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/rebuild-tiendas")
public class RebuildTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";
    private static final int PAUSA_ENTRE_PETICIONES_MS = 400;

    private static class TiendaData {
        int codigo; String denominacion; String sqlRowUUID; String direccion; BigDecimal lat; BigDecimal lon;
        String cp; String poblacion; String cadena; int prioridad; boolean disponible;
        int huecos1, huecos2, huecos3, huecos4;

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
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            out.println("ERROR: Acceso denegado. Tu sesiÃ³n ha expirado. Por favor, cierra sesiÃ³n y vuelve a entrar.");
            out.flush();
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        try {
            out.println("--- INICIANDO RECONSTRUCCIÃ“N DE TIENDAS (MODO CONSOLA) ---");
            out.flush();

            out.println("Paso 1/3: Leyendo todas las tiendas desde la base de datos local...");
            out.flush();
            
            List<TiendaData> tiendasEnMemoria = new ArrayList<>();
            try (Connection conn = DatabaseUtil.getConnection()) {
                String sql = "SELECT * FROM tiendas WHERE SqlRowUUID IS NOT NULL";
                try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tiendasEnMemoria.add(new TiendaData(rs));
                    }
                }
            }
            out.println("Se han cargado " + tiendasEnMemoria.size() + " tiendas en memoria.");
            out.println("----------------------------------------------------------");
            out.flush();

            out.println("Paso 2/3: Iniciando creaciÃ³n en SharePoint. Este proceso tardarÃ¡ aproximadamente 4 minutos...");
            out.flush();
            String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada.");
            
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
                
                out.print(".");
                if ((i + 1) % 100 == 0 || i == tiendasEnMemoria.size() - 1) {
                    out.println(" (" + (i + 1) + "/" + tiendasEnMemoria.size() + ")");
                    out.flush();
                }
            }
            
            out.println("\n----------------------------------------------------------");
            String successMessage = "Â¡Ã‰XITO! ReconstrucciÃ³n completada. Se han creado " + tiendasEnMemoria.size() + " tiendas.";
            out.println("Paso 3/3: " + successMessage);
            out.flush();

        } catch (Exception e) {
            out.println("\n\n--- Â¡ERROR CRÃTICO DURANTE LA RECONSTRUCCIÃ“N! ---");
            out.println("Mensaje del error: " + e.getMessage());
            e.printStackTrace(out);
            out.flush();
        }
    }
}

