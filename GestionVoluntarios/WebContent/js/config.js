/**
 * Fichero de configuración centralizado para el frontend.
 * Contiene las rutas (endpoints) de todos los servlets de la aplicación.
 */
const AppConfig = {
    // Raíz del contexto de la aplicación en el servidor Tomcat
    CONTEXT_ROOT: '/VoluntariosBAS',

    // Endpoints de la API (Servlets)
    API_ENDPOINTS: {
        LOGIN: '/login',
        LOGOUT: '/logout',
        NUEVO_VOLUNTARIO: '/nuevo-voluntario',
        MODIFICAR_DATOS: '/modificar-datos',
		DARSE_DE_BAJA: '/darse-de-baja',
        
        RECUPERAR_CLAVE: '/recuperar-clave',
        RESTABLECER_CLAVE: '/restablecer-clave',
        VERIFICAR_EMAIL: '/verificar-email',
        CONFIRMAR_CAMBIO_EMAIL: '/confirmar-cambio-email',
        AYUDA_ADMIN: '/ayuda-admin',

        USUARIO_ACTUAL: '/usuario-actual',
        VOLUNTARIO_DETALLES: '/voluntario-detalles',
        MIS_TURNOS: '/mis-turnos',
        GUARDAR_TURNOS: '/guardar-turnos',
        CAMPANA_ACTIVA: '/campana-activa',
        
        TODAS_LAS_TIENDAS: '/todas-las-tiendas',
        PUNTOS_DISPONIBLES: '/puntos-disponibles',
        
        ADMIN_CAMPANAS: '/admin-campanas',
        ADMIN_TIENDAS: '/admin-tiendas',
        ADMIN_VOLUNTARIOS: '/admin-voluntarios',
        ADMIN_ASIGNACIONES: '/admin-asignaciones',
        ADMIN_RESUMEN: '/admin-resumen',
        EXPORTAR_RESUMEN: '/exportar-resumen',
        ADMIN_DETALLE_TURNO: '/admin-detalle-turno',
        ADMIN_FILTROS_TIENDAS: '/admin-filtros-tiendas',
        INFORME_CAMPANA: '/informe-campana',
        REFRESCAR_POWERAPP: '/refrescar-powerapp',
		SOLICITAR_BAJA: '/solicitar-baja'
    },

    /**
     * Construye la URL completa para un endpoint específico.
     * @param {string} endpointKey - La clave del endpoint desde API_ENDPOINTS.
     * @returns {string} La URL completa.
     */
    getUrl: function(endpointKey) {
        return this.CONTEXT_ROOT + this.API_ENDPOINTS[endpointKey];
    }
};
